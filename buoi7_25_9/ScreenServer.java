package buoi7_25_9;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Server transport (NIO) tối ưu:
 * - NIO + Selector: 1 thread I/O phục vụ nhiều client
 * - Broadcaster thread: khi có frame mới -> enqueue tới per-client ring buffer
 * - Per-client ring buffer (drop oldest): client chậm không kéo tụt server
 * - Zero-copy: dùng ByteBuffer.wrap() trên cùng byte[] frame cho nhiều client (mỗi client giữ buffer riêng biệt về position/limit)
 * - Giao thức giữ nguyên: [int length][payload]
 *
 * Capture/Encode: JPEG + scale + FPS control (giống bước trước)
 */
public class ScreenServer {

    public static void main(String[] args) {
        new ScreenServer().start();
    }

    // ===== Tham số điều chỉnh nhanh =====
    private static final int PORT = 2345;

    // Encode & capture (từ bước trước)
    private static final int TARGET_MAX_WIDTH = 1280;
    private static final float JPEG_QUALITY = 0.70f;
    private static final int MAX_FPS = 20;
    private static final int IDLE_FPS = 5;
    private static final double CHANGE_RATIO_THRESHOLD = 0.01;

    // Transport
    private static final int CLIENT_RING_CAPACITY = 6;  // số khung tối đa buffer cho mỗi client
    private static final int SEND_SPIN_LIMIT = 64;      // tránh loop write vô tận 0 byte
    private static final int SO_RCVBUF = 512 * 1024;    // gợi ý: kernel recv buffer
    private static final int SO_SNDBUF = 2 * 1024 * 1024; // gợi ý: kernel send buffer

    // Khung hiện tại chia sẻ giữa threads
    private static final AtomicReference<Frame> CURRENT_FRAME = new AtomicReference<>();

    // ==== Server lifecycle ====
    public void start() {
        // 1) Khởi chạy capture/encoder
        Thread cap = new Thread(new ScreenCaptureEncoder(), "screen-capture-encoder");
        cap.setDaemon(true);
        cap.start();

        // 2) Khởi chạy NIO server + broadcaster
        try (Selector selector = Selector.open();
             ServerSocketChannel ssc = ServerSocketChannel.open()) {

            ssc.configureBlocking(false);
            ssc.bind(new InetSocketAddress(PORT));
            ssc.register(selector, SelectionKey.OP_ACCEPT);
            System.out.println("[Server] Listening on " + PORT);

            // Map quản lý session
            Map<SocketChannel, ClientSession> sessions = new ConcurrentHashMap<>();

            // Broadcaster: phát frame mới -> enqueue vào từng session + wakeup selector
            Thread broadcaster = new Thread(() -> runBroadcaster(selector, sessions), "broadcaster");
            broadcaster.setDaemon(true);
            broadcaster.start();

            // Vòng lặp NIO I/O
            while (true) {
                selector.select(); // chờ sự kiện I/O hoặc wakeup từ broadcaster
                Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                while (it.hasNext()) {
                    SelectionKey key = it.next();
                    it.remove();

                    try {
                        if (!key.isValid()) continue;

                        if (key.isAcceptable()) {
                            handleAccept(ssc, selector, sessions);
                        }
                        if (key.isWritable()) {
                            handleWrite(key, sessions);
                        }
                    } catch (CancelledKeyException ignored) {
                        // key đã bị hủy do đóng channel
                    } catch (Exception e) {
                        // lỗi bất ngờ -> đóng channel
                        closeKey(key, sessions, "[I/O] Error: " + e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[Server] Fatal NIO error: " + e.getMessage());
        }
    }

    // ==== NIO helpers ====

    private void handleAccept(ServerSocketChannel ssc, Selector selector, Map<SocketChannel, ClientSession> sessions) {
        try {
            SocketChannel ch = ssc.accept();
            if (ch == null) return;
            ch.configureBlocking(false);
            try {
                // Socket options (best-effort)
                ch.socket().setReceiveBufferSize(SO_RCVBUF);
                ch.socket().setSendBufferSize(SO_SNDBUF);
                ch.socket().setTcpNoDelay(true); // thường có lợi vì ta đã gom gói rõ ràng
            } catch (Exception ignored) {}

            ClientSession session = new ClientSession(ch, CLIENT_RING_CAPACITY);
            SelectionKey key = ch.register(selector, SelectionKey.OP_WRITE, session);
            sessions.put(ch, session);
            System.out.println("[Accept] " + ch.getRemoteAddress());
        } catch (IOException e) {
            System.err.println("[Accept] Error: " + e.getMessage());
        }
    }

    private void handleWrite(SelectionKey key, Map<SocketChannel, ClientSession> sessions) throws IOException {
        SocketChannel ch = (SocketChannel) key.channel();
        ClientSession sess = (ClientSession) key.attachment();
        if (sess == null) { closeKey(key, sessions, "[Write] No session"); return; }

        int spins = 0;
        while (true) {
            if (spins++ > SEND_SPIN_LIMIT) break; // tránh bận CPU
            ByteBuffer[] current = sess.peek();
            if (current == null) break; // hết dữ liệu, tạm bỏ OP_WRITE (nhưng broadcaster sẽ bật lại khi có frame)

            long written = ch.write(current);
            if (written == 0) break; // kernel full, chờ lần sau

            // kiểm tra đã gửi hết 2 buffers (header + payload)?
            boolean allSent = true;
            for (ByteBuffer bb : current) {
                if (bb.hasRemaining()) { allSent = false; break; }
            }
            if (allSent) {
                sess.pop();
            } else {
                // chưa gửi hết -> dừng tại đây, lần sau tiếp tục
                break;
            }
        }

        // Nếu queue trống, có thể tạm thời bỏ quan tâm OP_WRITE để giảm wakeup vô ích
        if (sess.isEmpty()) {
            key.interestOps(SelectionKey.OP_WRITE); // vẫn để OP_WRITE vì broadcaster sẽ wakeup ngay khi enqueue
        }
    }

    private void closeKey(SelectionKey key, Map<SocketChannel, ClientSession> sessions, String reason) {
        try {
            SocketChannel ch = (SocketChannel) key.channel();
            ClientSession sess = (ClientSession) key.attachment();
            if (sess != null) sess.close();
            if (ch != null) {
                sessions.remove(ch);
                System.out.println("[Close] " + ch.getRemoteAddress() + " - " + reason);
                ch.close();
            }
        } catch (Exception ignored) {
        }
        try { key.cancel(); } catch (Exception ignored) {}
    }

    // ==== Broadcaster ====

    /**
     * Mỗi khi có frame mới (seq thay đổi), broadcaster:
     * - Tạo header ByteBuffer (4 byte length)
     * - Tạo payload ByteBuffer wrap trên cùng byte[] JPEG
     * - Enqueue vào ring buffer của từng session (drop oldest nếu đầy)
     * - Gọi selector.wakeup() để thread I/O đẩy đi ngay
     */
    private void runBroadcaster(Selector selector, Map<SocketChannel, ClientSession> sessions) {
        int lastSeq = -1;

        while (true) {
            try {
                Frame f = CURRENT_FRAME.get();
                if (f == null || f.seq == lastSeq) {
                    Thread.sleep(2);
                    continue;
                }
                lastSeq = f.seq;

                // Chuẩn bị 2 buffer: header (length int) + payload (JPEG)
                ByteBuffer header = ByteBuffer.allocate(4);
                header.putInt(f.jpeg.length).flip();

                // payload: wrap trên cùng byte[] (zero-copy data), mỗi client sẽ có duplicate riêng
                ByteBuffer payload = ByteBuffer.wrap(f.jpeg);

                // Phát cho tất cả session
                for (ClientSession sess : sessions.values()) {
                    // Tạo duplicates riêng cho session (độc lập position/limit)
                    ByteBuffer h = header.duplicate();
                    ByteBuffer p = payload.duplicate();

                    // enqueue; nếu đầy -> drop oldest (giữ độ trễ thấp)
                    sess.enqueue(h, p);
                }

                // Đánh thức selector để push ngay
                selector.wakeup();
            } catch (InterruptedException ignored) {
            } catch (Exception e) {
                System.err.println("[Broadcaster] " + e.getMessage());
                try { Thread.sleep(5); } catch (InterruptedException ignored) {}
            }
        }
    }

    // ==== Client session & ring buffer ====

    private static final class ClientSession {
        private final SocketChannel ch;
        private final ArrayDeque<ByteBuffer[]> ring;
        private final int capacity;
        private volatile boolean closed = false;

        ClientSession(SocketChannel ch, int capacity) {
            this.ch = ch;
            this.capacity = Math.max(2, capacity);
            this.ring = new ArrayDeque<>(this.capacity);
        }

        // Enqueue header+payload; nếu đầy -> drop oldest
        synchronized void enqueue(ByteBuffer header, ByteBuffer payload) {
            if (closed) return;
            if (ring.size() >= capacity) {
                ring.pollFirst(); // drop oldest
            }
            ring.offerLast(new ByteBuffer[]{header, payload});
        }

        synchronized ByteBuffer[] peek() {
            return ring.peekFirst();
        }

        synchronized void pop() {
            ring.pollFirst();
        }

        synchronized boolean isEmpty() {
            return ring.isEmpty();
        }

        synchronized void close() {
            closed = true;
            ring.clear();
            try { ch.close(); } catch (IOException ignored) {}
        }
    }

    // ==== Frame immutable (share cho mọi client) ====
    private static final class Frame {
        final byte[] jpeg;
        final int seq;
        final int width;
        final int height;
        final long tsNanos;

        Frame(byte[] jpeg, int seq, int width, int height, long tsNanos) {
            this.jpeg = Objects.requireNonNull(jpeg);
            this.seq = seq;
            this.width = width;
            this.height = height;
            this.tsNanos = tsNanos;
        }
    }

    // ==== Capture + Encode (JPEG + scale + FPS control) ====
    private static final class ScreenCaptureEncoder implements Runnable {
        private Robot robot;
        private Rectangle area;
        private int seq = 0;
        private BufferedImage prevScaled = null;
        private final long frameIntervalActive = 1_000_000_000L / Math.max(1, MAX_FPS);
        private final long frameIntervalIdle   = 1_000_000_000L / Math.max(1, IDLE_FPS);

        @Override public void run() {
            try {
                robot = new Robot();
                area = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            } catch (Exception e) {
                System.err.println("[Capture] Init Robot failed: " + e.getMessage());
                return;
            }

            while (true) {
                long t0 = System.nanoTime();
                try {
                    BufferedImage src = robot.createScreenCapture(area);
                    BufferedImage scaled = scaleIfNeeded(src, TARGET_MAX_WIDTH);

                    boolean mostlyStatic = isMostlyStatic(prevScaled, scaled, CHANGE_RATIO_THRESHOLD);
                    prevScaled = scaled;

                    byte[] jpeg = encodeJpeg(scaled, JPEG_QUALITY);
                    CURRENT_FRAME.set(new Frame(jpeg, ++seq, scaled.getWidth(), scaled.getHeight(), System.nanoTime()));

                    long elapsed = System.nanoTime() - t0;
                    long target = mostlyStatic ? frameIntervalIdle : frameIntervalActive;
                    long sleep = target - elapsed;
                    if (sleep > 0) {
                        Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                    }
                } catch (Throwable t) {
                    System.err.println("[Capture] " + t.getMessage());
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                }
            }
        }

        private BufferedImage scaleIfNeeded(BufferedImage src, int maxWidth) {
            int sw = src.getWidth(), sh = src.getHeight();
            if (sw <= maxWidth) {
                return toRGB(src);
            }
            double s = maxWidth / (double) sw;
            int dw = (int) Math.round(sw * s);
            int dh = (int) Math.round(sh * s);
            BufferedImage dst = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = dst.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(src, 0, 0, dw, dh, null);
            g2.dispose();
            return dst;
        }

        private boolean isMostlyStatic(BufferedImage a, BufferedImage b, double thrRatio) {
            if (a == null || b == null) return false;
            if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;
            int w = b.getWidth(), h = b.getHeight();
            int step = Math.max(8, Math.min(w, h) / 120);
            int total = 0, changed = 0;
            for (int y = 0; y < h; y += step) {
                for (int x = 0; x < w; x += step) {
                    int p1 = a.getRGB(x, y), p2 = b.getRGB(x, y);
                    if (colorDiffGreater(p1, p2, 18)) changed++;
                    total++;
                }
            }
            double ratio = total == 0 ? 1.0 : changed / (double) total;
            return ratio < thrRatio;
        }

        private boolean colorDiffGreater(int c1, int c2, int thr) {
            int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = c1 & 0xFF;
            int r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = c2 & 0xFF;
            return Math.abs(r1 - r2) > thr || Math.abs(g1 - g2) > thr || Math.abs(b1 - b2) > thr;
        }

        private byte[] encodeJpeg(BufferedImage img, float q) throws IOException {
            BufferedImage rgb = toRGB(img);
            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
            if (!writers.hasNext()) throw new IOException("No JPEG writer");
            ImageWriter writer = writers.next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(Math.max(0f, Math.min(1f, q)));
            }
            try (java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream(64 * 1024);
                 MemoryCacheImageOutputStream out = new MemoryCacheImageOutputStream(bos)) {
                writer.setOutput(out);
                writer.write(null, new IIOImage(rgb, null, null), param);
                writer.dispose();
                out.flush();
                return bos.toByteArray();
            }
        }

        private BufferedImage toRGB(BufferedImage src) {
            if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
            BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = rgb.createGraphics();
            g2.drawImage(src, 0, 0, null);
            g2.dispose();
            return rgb;
        }
    }
}
