package buoi7_25_9.v2;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;

public class ScreenClient extends JFrame {
    private final JPanel screenPanel = new JPanel() {
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (currentImage != null) {
                g.drawImage(currentImage, 0, 0, getWidth(), getHeight(), null);
            } else {
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, getWidth(), getHeight());
                g.setColor(Color.WHITE);
                g.drawString("dang ket noi...", 20, 20);
            }

            // Hiển thị FPS
            g.setColor(Color.GREEN);
            g.setFont(new Font("Arial", Font.BOLD, 16));
            g.drawString("FPS: " + currentFPS, 20, 40);
        }
    };

    private BufferedImage currentImage;
    private float currentQuality = 0.7f;

    // Biến để tính FPS
    private volatile int frameCount = 0;
    private volatile int currentFPS = 0;

    public static void main(String[] args) {
        new ScreenClient("localhost", 2345);
    }

    public ScreenClient(String host, int port) {
        setTitle("client - " + host);
        setSize(1280, 768);
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setLocationRelativeTo(null);
        add(screenPanel);
        setVisible(true);

        new Thread(() -> receiveFrames(host, port)).start();
        new Timer(40, e -> screenPanel.repaint()).start();

        // Timer cập nhật FPS mỗi giây
        new Timer(1000, e -> {
            currentFPS = frameCount;
            frameCount = 0;
        }).start();
    }

    private void receiveFrames(String host, int port) {
        try (Socket socket = new Socket(host, port);
             DataInputStream in = new DataInputStream(socket.getInputStream());
             DataOutputStream out = new DataOutputStream(socket.getOutputStream())) {

            int screenWidth = in.readInt();
            int screenHeight = in.readInt();
            System.out.println("kt man hinh goc: " + screenWidth + "x" + screenHeight);

            long lastQualityCheckTime = System.currentTimeMillis();

            while (!socket.isClosed()) {
                long startTime = System.currentTimeMillis();
                boolean isFullFrame = in.readBoolean();
                int frameSeq = in.readInt();

                if (isFullFrame) {
                    int dataLength = in.readInt();
                    byte[] frameData = new byte[dataLength];
                    in.readFully(frameData);
                    BufferedImage img = ImageIO.read(new ByteArrayInputStream(frameData));
                    if (img != null) {
                        currentImage = img;
                    }
                } else {
                    int x = in.readInt();
                    int y = in.readInt();
                    int w = in.readInt();
                    int h = in.readInt();
                    int dataLength = in.readInt();
                    byte[] frameData = new byte[dataLength];
                    in.readFully(frameData);
                    BufferedImage deltaImg = ImageIO.read(new ByteArrayInputStream(frameData));
                    if (currentImage != null && deltaImg != null) {
                        Graphics2D g2d = currentImage.createGraphics();
                        g2d.drawImage(deltaImg, x, y, null);
                        g2d.dispose();
                    }
                }

                // tăng frameCount mỗi khi nhận được frame
                frameCount++;

                long latency = System.currentTimeMillis() - startTime;
                if (System.currentTimeMillis() - lastQualityCheckTime > 3000) {
                    if (latency > 150 && currentQuality > 0.3f) {
                        currentQuality -= 0.2f;
                        out.writeUTF("QUALITY:" + currentQuality);
                    } else if (latency < 50 && currentQuality < 0.9f) {
                        currentQuality += 0.1f;
                        out.writeUTF("QUALITY:" + currentQuality);
                    }
                    lastQualityCheckTime = System.currentTimeMillis();
                }
                if (frameSeq % 30 == 0) {
                    System.out.println("Frame " + frameSeq + " - do tre: " + latency + "ms - Chất lượng: " + (int) (currentQuality * 100) + "%");
                }
            }
        } catch (Exception e) {
            Graphics g = screenPanel.getGraphics();
            if (g != null) {
                g.setColor(Color.BLACK);
                g.fillRect(0, 0, screenPanel.getWidth(), screenPanel.getHeight());
                g.setColor(Color.RED);
                g.drawString("mat ket noi toiserver: " + e.getMessage(), 20, 20);
            }
        }
    }
}
