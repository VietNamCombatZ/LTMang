package buoi7_25_9.v3;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class ScreenServer {
    // ======= TUNABLES =======
    private static final int PORT = 2345;
    private static final int TARGET_FPS = 20;
    private static final int GOP = 10;                 // 1 key + (GOP-1) delta
    private static final int TILE_W = 96, TILE_H = 96; // size ô
    private static final int DIFF_THR = 12;            // ngưỡng MAD (0..255)
    private static final float Q_INIT = 0.65f, Q_MIN = 0.30f, Q_MAX = 0.85f;
    private static final double SCALE_INIT = 1.0, SCALE_MIN = 0.5, SCALE_MAX = 1.0;
    private static final long BAD_LATENCY_MS = 60;     // encode+send > 60ms coi là xấu
    private static final long ABR_WINDOW_MS = 2_000;   // đánh giá băng thông theo 2s

    public static void main(String[] args) {
        new ScreenServer().run();
    }

    private void run() {
        FrameProducer producer = new FrameProducer(TARGET_FPS);
        producer.start();

        try (ServerSocket server = new ServerSocket(PORT)) {
            System.out.println("Server started on " + PORT);
            while (true) {
                Socket soc = server.accept();
                new FrameSender(soc, producer).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    // ======= Frame Producer: chụp màn hình đều đặn, giữ reference mới nhất =======
    static class FrameProducer extends Thread {
        private final int fps;
        volatile BufferedImage lastFull; // đã scale về kích thước truyền
        final AtomicInteger seq = new AtomicInteger(0);

        FrameProducer(int fps) {
            this.fps = Math.max(1, fps);
            setName("frame-producer");
            setDaemon(true);
        }

        @Override
        public void run() {
            try {
                Robot r = new Robot();
                Rectangle screen = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
                long frameIntervalNs = 1_000_000_000L / fps;

                while (true) {
                    long t0 = System.nanoTime();
                    BufferedImage raw = r.createScreenCapture(screen);
                    // Giữ RGB để nén JPEG rẻ hơn
                    if (raw.getType() != BufferedImage.TYPE_INT_RGB) {
                        BufferedImage tmp = new BufferedImage(raw.getWidth(), raw.getHeight(), BufferedImage.TYPE_INT_RGB);
                        Graphics2D g2 = tmp.createGraphics();
                        g2.drawImage(raw, 0, 0, null);
                        g2.dispose();
                        raw = tmp;
                    }
                    lastFull = raw;
                    seq.incrementAndGet();

                    long dt = System.nanoTime() - t0;
                    long sleepNs = frameIntervalNs - dt;
                    if (sleepNs > 0) {
                        Thread.sleep(sleepNs / 1_000_000L, (int) (sleepNs % 1_000_000L));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    // ======= Frame Sender: packet hoá KEY/DELTA, ABR, nén JPEG =======
    static class FrameSender extends Thread {
        private final Socket soc;
        private final FrameProducer producer;

        // Trạng thái mỗi client
        private float quality = Q_INIT;
        private double scale = SCALE_INIT;
        private int frameIdx = 0;
        private BufferedImage reference; // reference để so sánh delta
        private long bytesInWindow = 0;
        private long windowStartMs = System.currentTimeMillis();

        FrameSender(Socket soc, FrameProducer producer) {
            this.soc = soc;
            this.producer = producer;
            setName("frame-sender-" + soc.getRemoteSocketAddress());
        }

        @Override
        public void run() {
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(soc.getOutputStream()))) {
                int lastSeenSeq = -1;
                while (!soc.isClosed()) {
                    int curSeq = producer.seq.get();
                    if (curSeq == lastSeenSeq) {
                        Thread.sleep(1);
                        continue;
                    }
                    lastSeenSeq = curSeq;

                    BufferedImage src = producer.lastFull;
                    if (src == null) continue;

                    BufferedImage cur = (scale == 1.0) ? src : resize(src, scale);

                    long tEncodeStart = System.currentTimeMillis();
                    boolean isKey = (frameIdx % GOP == 0) || reference == null ||
                            reference.getWidth() != cur.getWidth() || reference.getHeight() != cur.getHeight();

                    if (isKey) {
                        byte[] jpeg = encodeJpeg(cur, quality);
                        writeKey(out, curSeq, cur.getWidth(), cur.getHeight(), quality, jpeg);
                        bytesInWindow += 4 + jpeg.length + 1 + 4 + 2 + 2 + 4; // ước tính header
                        reference = deepCopy(cur);
                    } else {
                        List<TilePacket> tiles = diffTiles(reference, cur, TILE_W, TILE_H, DIFF_THR, quality);
                        // Nếu thay đổi quá nhiều ô, gửi KEY cho rẻ
                        int totalTiles = ((cur.getWidth() + TILE_W - 1) / TILE_W) * ((cur.getHeight() + TILE_H - 1) / TILE_H);
                        if (tiles.size() > totalTiles * 0.6) {
                            byte[] jpeg = encodeJpeg(cur, quality);
                            writeKey(out, curSeq, cur.getWidth(), cur.getHeight(), quality, jpeg);
                            bytesInWindow += 4 + jpeg.length + 1 + 4 + 2 + 2 + 4;
                            reference = deepCopy(cur);
                        } else {
                            writeDelta(out, curSeq, cur.getWidth(), cur.getHeight(), quality, TILE_W, TILE_H, tiles);
                            long sum = 1 + 4 + 2 + 2 + 4 + 2 + 2 + 4; // header cơ bản
                            for (TilePacket t : tiles) sum += 2 + 2 + 4 + t.data.length;
                            bytesInWindow += sum;
                            // cập nhật reference theo các tile thay đổi
                            applyTiles(reference, tiles, TILE_W, TILE_H);
                        }
                    }
                    out.flush();

                    long tEncodeSend = System.currentTimeMillis() - tEncodeStart;

                    // ABR đơn giản
                    adaptABR(tEncodeSend);

                    frameIdx++;
                }
            } catch (Exception e) {
                // client disconnect/network error
                // e.printStackTrace();
            } finally {
                try { soc.close(); } catch (Exception ignored) {}
            }
        }

        private void adaptABR(long encodeSendMs) {
            long now = System.currentTimeMillis();
            if (now - windowStartMs >= ABR_WINDOW_MS) {
                double kbps = (bytesInWindow * 8.0) / ((now - windowStartMs) / 1000.0) / 1000.0;
                // Heuristic: nếu latency xấu hoặc bitrate quá thấp thì hạ chất lượng
                if (encodeSendMs > BAD_LATENCY_MS || kbps < 1500) {
                    if (quality > Q_MIN + 1e-3) {
                        quality = Math.max(Q_MIN, quality - 0.1f);
                    } else if (scale > SCALE_MIN + 1e-3) {
                        scale = Math.max(SCALE_MIN, scale * 0.85);
                    }
                } else if (kbps > 4000 && encodeSendMs < BAD_LATENCY_MS / 2) {
                    // mạng tốt: nâng nhẹ
                    if (scale < SCALE_MAX - 1e-3) {
                        scale = Math.min(SCALE_MAX, scale / 0.9);
                    } else if (quality < Q_MAX - 1e-3) {
                        quality = Math.min(Q_MAX, quality + 0.05f);
                    }
                }
                bytesInWindow = 0;
                windowStartMs = now;
            }
        }

        // ======= Packet format =======
        // KEY frame: [byte type=0][int seq][short W][short H][float Q][int len][bytes JPEG]
        private void writeKey(DataOutputStream out, int seq, int w, int h, float q, byte[] jpeg) throws Exception {
            out.writeByte(0);
            out.writeInt(seq);
            out.writeShort(w);
            out.writeShort(h);
            out.writeFloat(q);
            out.writeInt(jpeg.length);
            out.write(jpeg);
        }

        // DELTA frame: [byte type=1][int seq][short W][short H][float Q][short tileW][short tileH][int N]
        //  N x { [short tx][short ty][int len][bytes JPEG_TILE] }
        private void writeDelta(DataOutputStream out, int seq, int w, int h, float q, int tw, int th, List<TilePacket> tiles) throws Exception {
            out.writeByte(1);
            out.writeInt(seq);
            out.writeShort(w);
            out.writeShort(h);
            out.writeFloat(q);
            out.writeShort(tw);
            out.writeShort(th);
            out.writeInt(tiles.size());
            for (TilePacket t : tiles) {
                out.writeShort(t.tx);
                out.writeShort(t.ty);
                out.writeInt(t.data.length);
                out.write(t.data);
            }
        }

        // ======= JPEG encode =======
        private static byte[] encodeJpeg(BufferedImage img, float quality) throws Exception {
            Iterator<ImageWriter> it = ImageIO.getImageWritersByFormatName("jpeg");
            ImageWriter writer = it.next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            if (param.canWriteCompressed()) {
                param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                param.setCompressionQuality(quality);
            }
            try (ByteArrayOutputStream bos = new ByteArrayOutputStream();
                 MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(bos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(img, null, null), param);
                writer.dispose();
                return bos.toByteArray();
            }
        }

        private static BufferedImage resize(BufferedImage src, double scale) {
            int w = (int) Math.round(src.getWidth() * scale);
            int h = (int) Math.round(src.getHeight() * scale);
            BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = dst.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(src, 0, 0, w, h, null);
            g2.dispose();
            return dst;
        }

        private static BufferedImage deepCopy(BufferedImage src) {
            BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics g = dst.getGraphics();
            g.drawImage(src, 0, 0, null);
            g.dispose();
            return dst;
        }

        // ======= Delta by tiles =======
        private static List<TilePacket> diffTiles(BufferedImage ref, BufferedImage cur, int tw, int th, int thr, float q) throws Exception {
            int W = cur.getWidth(), H = cur.getHeight();
            int nx = (W + tw - 1) / tw, ny = (H + th - 1) / th;
            ArrayList<TilePacket> res = new ArrayList<>();
            for (int ty = 0; ty < ny; ty++) {
                for (int tx = 0; tx < nx; tx++) {
                    int x = tx * tw, y = ty * th;
                    int w = Math.min(tw, W - x);
                    int h = Math.min(th, H - y);
                    if (isTileChanged(ref, cur, x, y, w, h, thr)) {
                        BufferedImage tile = cur.getSubimage(x, y, w, h);
                        byte[] data = encodeJpeg(tile, q);
                        res.add(new TilePacket(tx, ty, data));
                    }
                }
            }
            return res;
        }

        private static boolean isTileChanged(BufferedImage a, BufferedImage b, int x, int y, int w, int h, int thr) {
            long sum = 0; int cnt = 0;
            for (int j = 0; j < h; j++) {
                for (int i = 0; i < w; i++) {
                    int rgb1 = a.getRGB(x + i, y + j);
                    int rgb2 = b.getRGB(x + i, y + j);
                    int dr = Math.abs(((rgb1 >> 16) & 255) - ((rgb2 >> 16) & 255));
                    int dg = Math.abs(((rgb1 >> 8) & 255) - ((rgb2 >> 8) & 255));
                    int db = Math.abs((rgb1 & 255) - (rgb2 & 255));
                    sum += (dr + dg + db) / 3;
                    cnt++;
                }
            }
            int mad = (int) (sum / Math.max(1, cnt));
            return mad > thr;
        }

        private static void applyTiles(BufferedImage ref, List<TilePacket> tiles, int tw, int th) throws Exception {
            Graphics2D g2 = ref.createGraphics();
            for (TilePacket t : tiles) {
                BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(t.data));
                int x = t.tx * tw, y = t.ty * th;
                g2.drawImage(img, x, y, null);
            }
            g2.dispose();
        }
    }

    static class TilePacket {
        final int tx, ty;
        final byte[] data;
        TilePacket(int tx, int ty, byte[] data) { this.tx = tx; this.ty = ty; this.data = data; }
    }
}
