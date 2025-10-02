package buoi7_25_9.v2.patch2;



import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

public class ScreenClient extends JFrame {
    private static final String HOST = "localhost";
    private static final int PORT = 2345;

    private volatile BufferedImage canvas = null;
    private final AtomicInteger framesThisSecond = new AtomicInteger(0);
    private volatile int fps = 0;
    private float currentQuality = 0.7f;

    private final JPanel screenPanel = new JPanel() {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2 = (Graphics2D) g.create();

            if (canvas != null) {
                int pw = getWidth(), ph = getHeight();
                int iw = canvas.getWidth(), ih = canvas.getHeight();
                double s = Math.min(pw / (double) iw, ph / (double) ih);
                int w = (int) Math.round(iw * s), h = (int) Math.round(ih * s);
                int x = (pw - w) / 2, y = (ph - h) / 2;
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(canvas, x, y, w, h, null);
            } else {
                g2.setColor(Color.BLACK);
                g2.fillRect(0, 0, getWidth(), getHeight());
                g2.setColor(Color.WHITE);
                g2.drawString("connecting to server", 20, 20);
            }

            // HUD
            g2.setColor(Color.GREEN);
            g2.setFont(getFont().deriveFont(Font.BOLD, 16f));
            g2.drawString("FPS: " + fps, 20, 40);
            g2.dispose();
        }
    };

    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new ScreenClient(HOST, PORT));
    }

    public ScreenClient(String host, int port) {
        super("client - " + host);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1280, 768);
        setLocationRelativeTo(null);
        add(screenPanel, BorderLayout.CENTER);
        setVisible(true);

        new Thread(() -> receiveLoop(host, port), "receiver").start();

        // Repaint đều để UI mượt
        new Timer(40, e -> screenPanel.repaint()).start();
        // Cập nhật FPS mỗi giây
        new Timer(1000, e -> { fps = framesThisSecond.getAndSet(0); }).start();
    }

    private void receiveLoop(String host, int port) {
        try (Socket socket = new Socket(host, port);
             DataInputStream in = new DataInputStream(socket.getInputStream());
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

            int srcW = in.readInt();
            int srcH = in.readInt();
//            System.out.println("[Client] Root image size: " + srcW + "x" + srcH);

            long lastQualityCheck = System.currentTimeMillis();

            while (!socket.isClosed()) {
                long t0 = System.currentTimeMillis();

                boolean isFull = in.readBoolean();
                int seq = in.readInt();
                int w = in.readInt();
                int h = in.readInt();

                if (isFull) {
                    int len = in.readInt();
                    byte[] buf = in.readNBytes(len);
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(buf));
                    if (img != null) {
                        canvas = ensureRGB(img);
                    }
                } else {
                    int tileW = in.readInt();
                    int tileH = in.readInt();
                    int n = in.readInt();

                    if (canvas == null || canvas.getWidth() != w || canvas.getHeight() != h) {
                        canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                        Graphics2D g2 = canvas.createGraphics();
                        g2.setColor(Color.BLACK); g2.fillRect(0, 0, w, h);
                        g2.dispose();
                    }

                    Graphics2D g2 = canvas.createGraphics();
                    for (int i = 0; i < n; i++) {
                        int x = in.readInt();
                        int y = in.readInt();
                        int ww = in.readInt();
                        int hh = in.readInt();
                        int len = in.readInt();
                        byte[] buf = in.readNBytes(len);
                        BufferedImage tile = ImageIO.read(new ByteArrayInputStream(buf));
                        if (tile != null) {
                            g2.drawImage(tile, x, y, null);
                        }
                    }
                    g2.dispose();
                }

                framesThisSecond.incrementAndGet();

                long latency = System.currentTimeMillis() - t0;
                if (System.currentTimeMillis() - lastQualityCheck > 3000) {

                    // if latency > 150ms --> quality -0.1
                    if (latency > 150 && currentQuality > 0.3f) {
                        currentQuality -= 0.1f;
                        out.writeUTF("QUALITY:" + currentQuality);
                        out.flush();
                    } else if (latency < 50 && currentQuality < 0.9f) {
                        currentQuality += 0.05f;
                        out.writeUTF("QUALITY:" + currentQuality);
                        out.flush();
                    }
                    lastQualityCheck = System.currentTimeMillis();
                }

                if (seq % 30 == 0) {
                    System.out.println("[Client] seq=" + seq + " latency=" + latency + "ms quality=" + (int)(currentQuality*100) + "%");
                }
            }
        } catch (Exception e) {
            SwingUtilities.invokeLater(() -> {
                canvas = null;
                screenPanel.repaint();
            });
            System.err.println("[Client] disconnect: " + e.getMessage());
        }
    }

    private static BufferedImage ensureRGB(BufferedImage src) {
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics g = dst.getGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }
}