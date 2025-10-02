package buoi7_25_9.v3;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicInteger;

public class ScreenClient extends JFrame {
    private static final String HOST = "localhost";
    private static final int PORT = 2345;

    private final DisplayPanel display = new DisplayPanel();
    private final JLabel fpsLabel = new JLabel("FPS: --");
    private final AtomicInteger framesThisSecond = new AtomicInteger(0);

    public static void main(String[] args) {
        SwingUtilities.invokeLater(ScreenClient::new);
    }

    public ScreenClient() {
        super("Share Screen");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLayout(new BorderLayout());
        add(display, BorderLayout.CENTER);

        JPanel status = new JPanel(new FlowLayout(FlowLayout.LEFT));
        status.add(fpsLabel);
        add(status, BorderLayout.SOUTH);

        setSize(1000, 650);
        setLocationRelativeTo(null);
        setVisible(true);

        // Hiển thị FPS mỗi giây
        new Timer(1000, e -> fpsLabel.setText("FPS: " + framesThisSecond.getAndSet(0))).start();

        new Thread(this::receiveLoop, "receiver").start();
    }

    private void receiveLoop() {
        try (Socket soc = new Socket(HOST, PORT);
             DataInputStream in = new DataInputStream(soc.getInputStream())) {

            BufferedImage canvas = null; // khung hiện tại
            while (true) {
                byte frameType = in.readByte(); // 0=KEY, 1=DELTA
                int seq = in.readInt();
                int w = in.readShort() & 0xFFFF;
                int h = in.readShort() & 0xFFFF;
                float q = in.readFloat();

                if (frameType == 0) {
                    int len = in.readInt();
                    byte[] buf = in.readNBytes(len);
                    BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(buf));
                    if (img == null) continue;
                    canvas = ensureType(img, BufferedImage.TYPE_INT_RGB);
                    display.setFrame(canvas);
                } else {
                    int tileW = in.readShort() & 0xFFFF;
                    int tileH = in.readShort() & 0xFFFF;
                    int n = in.readInt();
                    if (canvas == null || canvas.getWidth() != w || canvas.getHeight() != h) {
                        canvas = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                        Graphics2D g2 = canvas.createGraphics();
                        g2.setColor(Color.BLACK);
                        g2.fillRect(0,0,w,h);
                        g2.dispose();
                    }
                    Graphics2D g2 = canvas.createGraphics();
                    for (int i = 0; i < n; i++) {
                        int tx = in.readShort() & 0xFFFF;
                        int ty = in.readShort() & 0xFFFF;
                        int len = in.readInt();
                        byte[] buf = in.readNBytes(len);
                        BufferedImage tile = ImageIO.read(new java.io.ByteArrayInputStream(buf));
                        if (tile != null) {
                            int x = tx * tileW, y = ty * tileH;
                            g2.drawImage(tile, x, y, null);
                        }
                    }
                    g2.dispose();
                    display.setFrame(canvas);
                }
                framesThisSecond.incrementAndGet();
            }
        } catch (Exception e) {
            // e.printStackTrace();
        }
    }

    private static BufferedImage ensureType(BufferedImage src, int type) {
        if (src.getType() == type) return src;
        BufferedImage dst = new BufferedImage(src.getWidth(), src.getHeight(), type);
        Graphics g = dst.getGraphics();
        g.drawImage(src, 0, 0, null);
        g.dispose();
        return dst;
    }

    // Panel vẽ ảnh, fit center giữ tỉ lệ, mượt
    static class DisplayPanel extends JPanel {
        private volatile BufferedImage frame;

        void setFrame(BufferedImage img) {
            this.frame = img;
            SwingUtilities.invokeLater(this::repaint);
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            BufferedImage img = frame;
            if (img == null) return;

            int pw = getWidth(), ph = getHeight();
            int iw = img.getWidth(), ih = img.getHeight();
            double scale = Math.min(pw / (double) iw, ph / (double) ih);
            int w = (int) Math.round(iw * scale);
            int h = (int) Math.round(ih * scale);
            int x = (pw - w) / 2, y = (ph - h) / 2;

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(img, x, y, w, h, null);
            g2.dispose();
        }
    }
}
