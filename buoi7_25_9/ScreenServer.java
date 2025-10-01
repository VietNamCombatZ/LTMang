package buoi7_25_9;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Tối ưu: giảm băng thông & CPU
 * - PNG -> JPEG (quality tùy chỉnh) => giảm 5–10x kích thước
 * - Giới hạn FPS (~15–30), hạ FPS khi màn hình không đổi nhiều
 * - Scale về độ phân giải mục tiêu (ví dụ: maxWidth = 1280) trước khi encode
 * - Dùng AtomicReference<Frame> (immutable) + volatile, tránh race condition
 * - DataOutputStream tạo 1 lần/connection; không clone byte[] lặp lại
 * - try-with-resources, log lỗi gọn gàng
 *
 * Giao thức KHÔNG đổi: client vẫn đọc int length + payload (ảnh JPEG)
 */
public class ScreenServer {

    public static void main(String[] args) {
        new ScreenServer().start();
    }

    // ====== Tham số tối ưu (bạn có thể chỉnh tuỳ ý) ======
    private static final int PORT = 2345;
    private static final int TARGET_MAX_WIDTH = 1280; // scale về <= 1280 px chiều ngang (nếu màn hình lớn)
    private static final float JPEG_QUALITY = 0.70f;  // 0.0–1.0 (0.7 là cân bằng tốt cho desktop)
    private static final int MAX_FPS = 20;            // 15–30 fps; WAN yếu thì 10–20
    private static final int IDLE_FPS = 5;            // khi màn hình gần như không đổi, hạ fps để tiết kiệm
    private static final double CHANGE_RATIO_THRESHOLD = 0.01; // <1% pixel đổi => coi như tĩnh

    // Khung hình hiện tại cho tất cả client
    private static final AtomicReference<Frame> CURRENT_FRAME = new AtomicReference<>();

    public void start() {
        // 1) Thread chụp & encode
        Thread captureThread = new Thread(new ScreenCaptureEncoder(), "screen-capture-encoder");
        captureThread.setDaemon(true);
        captureThread.start();

        // 2) Socket server
        try (ServerSocket server = new ServerSocket(PORT)) {
            System.out.println("[Server] Listening on port " + PORT);
            while (true) {
                try {
                    Socket soc = server.accept();
                    System.out.println("[Server] Client connected: " + soc.getRemoteSocketAddress());
                    new ScreenSender(soc).start();
                } catch (IOException e) {
                    System.err.println("[Server] Accept error: " + e.getMessage());
                }
            }
        } catch (IOException e) {
            System.err.println("[Server] Fatal: " + e.getMessage());
        }
    }

    /** Khung hình bất biến để chia sẻ an toàn giữa các thread. */
    private static final class Frame {
        final byte[] jpeg;     // dữ liệu đã nén JPEG
        final int seq;         // số thứ tự khung
        final int width;       // kích thước (sau scale)
        final int height;
        final long tsNanos;    // timestamp

        Frame(byte[] jpeg, int seq, int width, int height, long tsNanos) {
            this.jpeg = Objects.requireNonNull(jpeg);
            this.seq = seq;
            this.width = width;
            this.height = height;
            this.tsNanos = tsNanos;
        }
    }

    /** Thread chụp màn hình, scale & encode JPEG, cập nhật CURRENT_FRAME. */
    private static final class ScreenCaptureEncoder implements Runnable {
        private Robot robot;
        private Rectangle captureArea;
        private int seq = 0;
        private BufferedImage prevScaled = null; // giữ khung trước (đã scale) để ước lượng thay đổi
        private final long frameIntervalActive = 1_000_000_000L / Math.max(1, MAX_FPS);
        private final long frameIntervalIdle   = 1_000_000_000L / Math.max(1, IDLE_FPS);

        @Override
        public void run() {
            try {
                robot = new Robot();
                captureArea = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            } catch (Exception e) {
                System.err.println("[Capture] Cannot init Robot: " + e.getMessage());
                return;
            }

            while (true) {
                long start = System.nanoTime();
                try {
                    // 1) Chụp màn hình gốc
                    BufferedImage src = robot.createScreenCapture(captureArea);

                    // 2) Scale về kích thước mục tiêu nếu cần (giảm encode & băng thông)
                    BufferedImage scaled = scaleIfNeeded(src, TARGET_MAX_WIDTH);

                    // 3) Ước lượng mức thay đổi (để hạ FPS nếu tĩnh)
                    boolean mostlyStatic = isMostlyStatic(prevScaled, scaled, CHANGE_RATIO_THRESHOLD);
                    prevScaled = scaled;

                    // 4) Encode JPEG với quality cấu hình (MemoryCacheImageOutputStream để giảm copy)
                    byte[] jpeg = encodeJpeg(scaled, JPEG_QUALITY);

                    // 5) Cập nhật khung dùng chung
                    int width = scaled.getWidth();
                    int height = scaled.getHeight();
                    CURRENT_FRAME.set(new Frame(jpeg, ++seq, width, height, System.nanoTime()));

                    // 6) Điều tiết FPS (active vs idle)
                    long elapsed = System.nanoTime() - start;
                    long targetInterval = mostlyStatic ? frameIntervalIdle : frameIntervalActive;
                    long sleepNanos = targetInterval - elapsed;
                    if (sleepNanos > 0) {
                        Thread.sleep(sleepNanos / 1_000_000L, (int) (sleepNanos % 1_000_000L));
                    }
                } catch (Throwable t) {
                    // bắt mọi lỗi để thread không chết
                    System.err.println("[Capture] Error: " + t.getMessage());
                    try { Thread.sleep(50); } catch (InterruptedException ignored) {}
                }
            }
        }

        /** Scale về maxWidth, giữ tỉ lệ; nếu nhỏ hơn thì giữ nguyên. */
        private BufferedImage scaleIfNeeded(BufferedImage src, int maxWidth) {
            int sw = src.getWidth();
            int sh = src.getHeight();
            if (sw <= maxWidth) {
                // Đảm bảo kiểu ảnh phù hợp cho JPEG (RGB, không alpha)
                if (src.getType() != BufferedImage.TYPE_INT_RGB) {
                    BufferedImage rgb = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
                    Graphics2D g2 = rgb.createGraphics();
                    g2.drawImage(src, 0, 0, null);
                    g2.dispose();
                    return rgb;
                }
                return src;
            }
            double scale = maxWidth / (double) sw;
            int dw = (int) Math.round(sw * scale);
            int dh = (int) Math.round(sh * scale);

            BufferedImage dst = new BufferedImage(dw, dh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = dst.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(src, 0, 0, dw, dh, null);
            g2.dispose();
            return dst;
        }

        /**
         * So sánh nhanh để xem frame có "gần như tĩnh" hay không.
         * Cách làm nhẹ: lấy mẫu thưa (grid) và so màu, tính tỉ lệ điểm thay đổi.
         */
        private boolean isMostlyStatic(BufferedImage a, BufferedImage b, double threshRatio) {
            if (a == null || b == null) return false;
            if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) return false;

            final int w = b.getWidth();
            final int h = b.getHeight();

            // Lấy mẫu thưa (mỗi 16 px); điều chỉnh nếu muốn nhạy hơn
            final int step = Math.max(8, Math.min(w, h) / 120);
            int total = 0;
            int changed = 0;
            for (int y = 0; y < h; y += step) {
                for (int x = 0; x < w; x += step) {
                    int p1 = a.getRGB(x, y);
                    int p2 = b.getRGB(x, y);
                    if (colorDiffGreater(p1, p2, 18)) { // ngưỡng 18/255 mỗi kênh (mềm dẻo)
                        changed++;
                    }
                    total++;
                }
            }
            double ratio = (total == 0) ? 1.0 : (changed / (double) total);
            return ratio < threshRatio;
        }

        private boolean colorDiffGreater(int c1, int c2, int thr) {
            int r1 = (c1 >> 16) & 0xFF, g1 = (c1 >> 8) & 0xFF, b1 = (c1) & 0xFF;
            int r2 = (c2 >> 16) & 0xFF, g2 = (c2 >> 8) & 0xFF, b2 = (c2) & 0xFF;
            return (Math.abs(r1 - r2) > thr) || (Math.abs(g1 - g2) > thr) || (Math.abs(b1 - b2) > thr);
        }

        /** Encode JPEG với quality tùy chỉnh, tránh alpha. */
        private byte[] encodeJpeg(BufferedImage img, float quality) throws IOException {
            // Bảo đảm TYPE_INT_RGB (JPEG không hỗ trợ alpha)
            BufferedImage rgb = (img.getType() == BufferedImage.TYPE_INT_RGB) ? img
                    : toRGB(img);

            Iterator<ImageWriter> writers = ImageIO.getImageWritersByFormatName("jpg");
            if (!writers.hasNext()) throw new IOException("No JPEG writer found");
            ImageWriter writer = writers.next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(Math.max(0.0f, Math.min(1.0f, quality)));
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
            BufferedImage rgb = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = rgb.createGraphics();
            g2.drawImage(src, 0, 0, null);
            g2.dispose();
            return rgb;
        }
    }

    /** Gửi khung hiện tại cho 1 client (mỗi connection một thread nhẹ). */
    private static final class ScreenSender extends Thread {
        private final Socket soc;
        private int lastSeq = -1;

        ScreenSender(Socket soc) {
            super("screen-sender-" + soc.getRemoteSocketAddress());
            this.soc = soc;
        }

        @Override
        public void run() {
            try (Socket s = this.soc;
                 DataOutputStream out = new DataOutputStream(s.getOutputStream())) {

                while (!s.isClosed()) {
                    // Lấy khung hiện tại
                    Frame f = CURRENT_FRAME.get();
                    if (f == null) {
                        // Chưa có khung (server mới khởi động)
                        Thread.sleep(10);
                        continue;
                    }

                    // Chỉ gửi khi có khung mới (seq thay đổi)
                    if (f.seq == lastSeq) {
                        Thread.sleep(5); // nhường CPU, giảm busy-wait
                        continue;
                    }

                    // Gửi "độ dài" + "payload" (giữ nguyên giao thức cũ, client không cần đổi)
                    out.writeInt(f.jpeg.length);
                    out.write(f.jpeg);
                    out.flush();

                    lastSeq = f.seq;
                }
            } catch (Exception e) {
                System.err.println("[Sender] Client " + soc.getRemoteSocketAddress() + " disconnected: " + e.getMessage());
            }
        }
    }
}
