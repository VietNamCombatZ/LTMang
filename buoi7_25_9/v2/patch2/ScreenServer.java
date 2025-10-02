package buoi7_25_9.v2.patch2;


import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

public class ScreenServer {
    //const parameters
    private static final int PORT = 2345;
    private static final int TARGET_FPS = 20;
    private static final int GOP = 10;               // 1 key + (GOP-1) delta
    private static final int TILE_W = 96, TILE_H = 96;
    private static final float FULL_FRAME_THRESHOLD = 0.60f; // nếu >60% tile đổi -> gửi key
    private static final int DIFF_THR = 0;           // 0 = so pixel tuyệt đối; >0 = cho phép sai khác nhỏ
    private static final float Q_INIT = 0.70f, Q_MIN = 0.30f, Q_MAX = 0.90f;
    private static final double SCALE_INIT = 1.0, SCALE_MIN = 0.50, SCALE_MAX = 1.0;
    private static final long BAD_MS = 60;           // encode+flush > 60ms coi là xấu

    // state
    private final AtomicReference<ScreenFrame> latestFrame = new AtomicReference<>();
    private final List<ClientHandler> clients = Collections.synchronizedList(new ArrayList<>());

    public static void main(String[] args) throws Exception {
        new ScreenServer().start();
    }

    public void start() throws Exception {
        System.out.println("[Server] Starting server");
        new Thread(new CaptureTask(), "capture").start();

        try (ServerSocket server = new ServerSocket(PORT)) {
            System.out.println("[Server] Listening on " + PORT);
            while (true) {
                Socket clientSocket = server.accept();
                ClientHandler handler = new ClientHandler(clientSocket);
                clients.add(handler);
                new Thread(handler, "sender-" + clientSocket.getRemoteSocketAddress()).start();
                System.out.println("[Server] New connection, connection count: " + clients.size());
            }
        }
    }

    static class ScreenFrame {
        final BufferedImage rawImage; // TYPE_INT_RGB
        final int sequence;
        ScreenFrame(BufferedImage rawImage, int sequence) {
            this.rawImage = rawImage;
            this.sequence = sequence;
        }
    }

    //Capture screen
    class CaptureTask implements Runnable {
        private int sequence = 0;
        public void run() {
            try {
                Robot robot = new Robot();
                Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
                long frameIntervalNs = 1_000_000_000L / Math.max(1, TARGET_FPS);
                while (true) {
                    long t0 = System.nanoTime();
                    BufferedImage screen = robot.createScreenCapture(screenRect);
                    // Ép RGB (JPEG no need alpha color)
                    if (screen.getType() != BufferedImage.TYPE_INT_RGB) {
                        BufferedImage rgb = new BufferedImage(screen.getWidth(), screen.getHeight(), BufferedImage.TYPE_INT_RGB);
                        Graphics g = rgb.getGraphics();
                        g.drawImage(screen, 0, 0, null);
                        g.dispose();
                        screen = rgb;
                    }
                    latestFrame.set(new ScreenFrame(screen, ++sequence));

                    long dt = System.nanoTime() - t0;
                    long sleep = frameIntervalNs - dt;
                    if (sleep > 0) {
                        Thread.sleep(sleep / 1_000_000L, (int) (sleep % 1_000_000L));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }


    // Send data for client : GOP ((group of pictúe) + delta tile
    class ClientHandler implements Runnable {
        private final Socket socket;
        private float quality = Q_INIT;
        private double scale = SCALE_INIT;
        private int lastSentSeq = -1;
        private int framesSinceKey = 0;
        private BufferedImage lastSentImage = null; // scaled reference

        ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            System.out.println("[Client] " + socket.getRemoteSocketAddress());
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream(), 64 * 1024));
                 DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {

                ScreenFrame first;
                while ((first = latestFrame.get()) == null) Thread.sleep(20);

                // raw frame size receive from server to client
                out.writeInt(first.rawImage.getWidth());
                out.writeInt(first.rawImage.getHeight());
                out.flush();

                // send first key frame
                sendKey(out, first);

                while (!socket.isClosed()) {
                    // receive quality report from client
//                    if (in.available() > 0) {
//                        String cmd = in.readUTF();
//                        if (cmd.startsWith("QUALITY:")) {
//                            try {
//                                float q = Float.parseFloat(cmd.substring(8));
//                                quality = clamp(q, Q_MIN, Q_MAX);
//                                System.out.println("[Client " + socket.getInetAddress() + "] quality: " + (int) (quality * 100) + "%");
//                            } catch (Exception ignore) {}
//                        }
//                    }

                    ScreenFrame cur = latestFrame.get();
                    if (cur == null || cur.sequence <= lastSentSeq) {
                        Thread.sleep(2);
                        continue;
                    }

                    boolean forceKey = framesSinceKey >= (GOP - 1)
                            || lastSentImage == null;

                    long tStart = System.nanoTime();
                    if (forceKey) {
                        sendKey(out, cur);
                        framesSinceKey = 0;
                    } else {

                        // compare with scaled image for tile map
                        BufferedImage scaled = resizeTo(cur.rawImage, scale);
                        List<Rect> tiles = diffTiles(lastSentImage, scaled, TILE_W, TILE_H, DIFF_THR);

                        int totalTiles = ((scaled.getWidth() + TILE_W - 1) / TILE_W)
                                * ((scaled.getHeight() + TILE_H - 1) / TILE_H);

                        //if tile changed > threshold --> send whole key frame
                        if ((float) tiles.size() / Math.max(1, totalTiles) > FULL_FRAME_THRESHOLD) {
                            sendKey(out, cur);
                            framesSinceKey = 0;
                        } else {
                            sendDelta(out, cur.sequence, scaled.getWidth(), scaled.getHeight(), tiles, scaled);
                            lastSentImage = scaled;
                            framesSinceKey++;
                        }
                    }
                    long sendMs = (System.nanoTime() - tStart) / 1_000_000L;
                    adaptABR(sendMs);

                }
            } catch (Exception e) {
                System.out.println("[Client] disconnect " + socket.getRemoteSocketAddress());
            } finally {
                clients.remove(this);
                try { socket.close(); } catch (IOException ignore) {}
                System.out.println("[Server] Tổng client: " + clients.size());
            }
        }

        private void sendKey(DataOutputStream out, ScreenFrame frame) throws IOException {
            BufferedImage scaled = resizeTo(frame.rawImage, scale);
            byte[] jpeg = encodeJpeg(scaled, quality);


            out.writeBoolean(true);
            out.writeInt(frame.sequence);
            out.writeInt(scaled.getWidth());
            out.writeInt(scaled.getHeight());
            out.writeInt(jpeg.length);
            out.write(jpeg);
            out.flush();

            lastSentImage = scaled;
            lastSentSeq = frame.sequence;
        }

        private void sendDelta(DataOutputStream out, int seq, int w, int h, List<Rect> tiles, BufferedImage scaled) throws IOException {

            out.writeBoolean(false);
            out.writeInt(seq);
            out.writeInt(w);
            out.writeInt(h);
            out.writeInt(TILE_W);
            out.writeInt(TILE_H);
            out.writeInt(tiles.size());

            for (Rect r : tiles) {
                BufferedImage sub = scaled.getSubimage(r.x, r.y, r.w, r.h);
                byte[] data = encodeJpeg(sub, quality);

                out.writeInt(r.x);
                out.writeInt(r.y);
                out.writeInt(r.w);
                out.writeInt(r.h);
                out.writeInt(data.length);
                out.write(data);
            }
            out.flush();
            lastSentSeq = seq;

            if (seq % 30 == 0) {
                System.out.println("[Delta] seq=" + seq + " tiles=" + tiles.size());
            }
        }

        private void adaptABR(long sendMs) {

            // bad network --> quality decrease, then scale decrease
            if (sendMs > BAD_MS) {
                if (quality > Q_MIN + 1e-3) {
                    quality = (float) Math.max(Q_MIN, quality - 0.1f);
                } else if (scale > SCALE_MIN + 1e-3) {
                    scale = Math.max(SCALE_MIN, scale * 0.85);
                }
            } else if (sendMs < BAD_MS / 2) {
                if (scale < SCALE_MAX - 1e-3) {
                    scale = Math.min(SCALE_MAX, scale / 0.9);
                } else if (quality < Q_MAX - 1e-3) {
                    quality = Math.min(Q_MAX, quality + 0.05f);
                }
            }
        }


//        private float clamp(float v, float lo, float hi) { return Math.max(lo, Math.min(hi, v)); }

        private BufferedImage resizeTo(BufferedImage src, double s) {
            if (s == 1.0) return src;
            int w = Math.max(1, (int) Math.round(src.getWidth() * s));
            int h = Math.max(1, (int) Math.round(src.getHeight() * s));
            BufferedImage dst = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g2 = dst.createGraphics();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(src, 0, 0, w, h, null);
            g2.dispose();
            return dst;
        }

        private List<Rect> diffTiles(BufferedImage a, BufferedImage b, int tw, int th, int thr) {
            ArrayList<Rect> res = new ArrayList<>();
            if (a == null || a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {

                // if no reference image or different size -> all tiles changed
                for (int y = 0; y < b.getHeight(); y += th) {
                    for (int x = 0; x < b.getWidth(); x += tw) {
                        int ww = Math.min(tw, b.getWidth() - x);
                        int hh = Math.min(th, b.getHeight() - y);
                        res.add(new Rect(x, y, ww, hh));
                    }
                }
                return res;
            }
            int H = b.getHeight(), W = b.getWidth();
            for (int y = 0; y < H; y += th) {
                for (int x = 0; x < W; x += tw) {
                    int ww = Math.min(tw, W - x);
                    int hh = Math.min(th, H - y);
                    if (!isBlockSame(a, b, x, y, ww, hh, thr)) {
                        res.add(new Rect(x, y, ww, hh));
                    }
                }
            }
            return res;
        }

        private boolean isBlockSame(BufferedImage oldImg, BufferedImage newImg, int x, int y, int w, int h, int thr) {
            // compare each pixel in block, allowing small difference if thr>0
            if (thr <= 0) {
                for (int j = 0; j < h; j++) {
                    for (int i = 0; i < w; i++) {
                        if (oldImg.getRGB(x + i, y + j) != newImg.getRGB(x + i, y + j)) return false;
                    }
                }
                return true;
            } else {
                long sum = 0; int cnt = 0;
                for (int j = 0; j < h; j++) {
                    for (int i = 0; i < w; i++) {
                        int a = oldImg.getRGB(x + i, y + j);
                        int b = newImg.getRGB(x + i, y + j);
                        int dr = Math.abs(((a>>16)&255) - ((b>>16)&255));
                        int dg = Math.abs(((a>>8)&255)  - ((b>>8)&255));
                        int db = Math.abs((a&255)        - (b&255));
                        sum += (dr+dg+db)/3; cnt++;
                    }
                }
                return (sum / Math.max(1, cnt)) <= thr;
            }
        }

        private byte[] encodeJpeg(BufferedImage image, float q) throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(q);
            try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(baos)) {
                writer.setOutput(ios);
                writer.write(null, new IIOImage(image, null, null), param);
            } finally {
                writer.dispose();
            }
            return baos.toByteArray();
        }
    }

    static class Rect {
        final int x, y, w, h;
        Rect(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }
    }
}

