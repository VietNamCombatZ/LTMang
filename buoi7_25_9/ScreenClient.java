package buoi7_25_9;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Sửa lỗi Client UI:
 * - Không đọc mạng trong paint.
 * - Dùng JPanel + paintComponent để chỉ vẽ.
 * - Nhận dữ liệu ở thread riêng, readFully để đọc đủ n byte.
 * - Tạo DataInputStream 1 lần; chỉ repaint khi có frame mới.
 * - Giữ tỉ lệ ảnh; không gọi repaint() bên trong paint.
 */
public class ScreenClient extends JFrame {

    private volatile BufferedImage latestFrame;   // khung hình hiện tại (thread-safe qua volatile)
    private final VideoPanel panel = new VideoPanel();
    private Socket socket;
    private DataInputStream in;
    private volatile long latestFrameRecvNs = 0L; // thời điểm KHUNG MỚI được nhận xong (ns)




    // Đổi host/port nếu cần
    private static final String HOST = "localhost";
    private static final int PORT = 2345;

    public static void main(String[] args) {
        SwingUtilities.invokeLater(ScreenClient::new);
    }

    public ScreenClient() {
        setTitle("Share Screen (Client)");
        setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        setSize(900, 600);
        setLocationRelativeTo(null);
        setLayout(new BorderLayout());
        add(panel, BorderLayout.CENTER);
        setVisible(true);

        // Kết nối & bắt đầu vòng nhận khung hình (ở thread riêng)
        new Thread(this::receiveLoop, "screen-receiver").start();

        // Đảm bảo đóng tài nguyên khi cửa sổ tắt
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override public void windowClosing(java.awt.event.WindowEvent e) {
                safeClose();
            }
        });
    }

    // ----- 1) Bộ đếm FPS cho receive -----
    private final FpsMeter recvFps = new FpsMeter(1_000_000_000L);   // cửa sổ 1 giây
    // ----- 2) Bộ đếm FPS cho render -----
    private final FpsMeter renderFps = new FpsMeter(1_000_000_000L); // cửa sổ 1 giây

    private void receiveLoop() {
        try {
            socket = new Socket(HOST, PORT);
            in = new DataInputStream(socket.getInputStream());

            while (!socket.isClosed()) {
                int n = in.readInt();
                if (n <= 0 || n > (50 * 1024 * 1024)) throw new IOException("Invalid frame size: " + n);

                byte[] buf = new byte[n];
                in.readFully(buf);

                BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(buf));
                if (img != null) {
                    latestFrame = img;
                    latestFrameRecvNs = System.nanoTime(); // mốc thời gian nhận xong khung
                    recvFps.onTick(latestFrameRecvNs);     // cập nhật receive FPS
                    panel.repaint();
                }
            }
        } catch (Exception e) {
            SwingUtilities.invokeLater(() ->
                    JOptionPane.showMessageDialog(this, "Mất kết nối server: " + e.getMessage(), "Lỗi", JOptionPane.ERROR_MESSAGE)
            );
        } finally {
            safeClose();
        }
    }

    private void safeClose() {
        try { if (in != null) in.close(); } catch (IOException ignored) {}
        try { if (socket != null && !socket.isClosed()) socket.close(); } catch (IOException ignored) {}
    }

    private class VideoPanel extends JPanel {
        private static final int MARGIN = 20;
        private boolean showHud = true;

        VideoPanel() {
            // phím H bật/tắt HUD
            setFocusable(true);
            addKeyListener(new java.awt.event.KeyAdapter() {
                @Override public void keyPressed(java.awt.event.KeyEvent e) {
                    if (e.getKeyChar() == 'h' || e.getKeyChar() == 'H') {
                        showHud = !showHud;
                        repaint();
                    }
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            BufferedImage img = latestFrame;
            if (img == null) {
                drawCentered((Graphics2D) g, "Đang chờ khung hình từ server... (nhấn H để ẩn/hiện HUD)");
                return;
            }

            // vẽ ảnh giữ tỉ lệ
            int availW = Math.max(1, getWidth() - 2 * MARGIN);
            int availH = Math.max(1, getHeight() - 2 * MARGIN);
            double s = Math.min(availW / (double) img.getWidth(), availH / (double) img.getHeight());
            int drawW = (int) Math.round(img.getWidth() * s);
            int drawH = (int) Math.round(img.getHeight() * s);
            int x = (getWidth() - drawW) / 2;
            int y = (getHeight() - drawH) / 2;

            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(img, x, y, drawW, drawH, null);

            // cập nhật render FPS sau khi vẽ
            long now = System.nanoTime();
            renderFps.onTick(now);

            if (showHud) {
                // Độ trễ "age" từ lúc nhận khung đến lúc vẽ (ms)
                double ageMs = (now - latestFrameRecvNs) / 1_000_000.0;

                String hud = String.format(
                        "Recv FPS: %.1f | Render FPS: %.1f | Age: %.1f ms (nhấn H ẩn/hiện)",
                        recvFps.getFps(), renderFps.getFps(), ageMs
                );
                drawHud(g2, hud);
            }

            g2.dispose();
        }

        private void drawCentered(Graphics2D g2, String msg) {
            g2.setFont(getFont().deriveFont(Font.PLAIN, 14f));
            FontMetrics fm = g2.getFontMetrics();
            int x = (getWidth() - fm.stringWidth(msg)) / 2;
            int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            g2.drawString(msg, x, y);
        }

        private void drawHud(Graphics2D g2, String text) {
            g2.setFont(getFont().deriveFont(Font.BOLD, 13f));
            FontMetrics fm = g2.getFontMetrics();
            int pad = 6;
            int w = fm.stringWidth(text) + pad * 2;
            int h = fm.getHeight() + pad * 2;
            int x = 10, y = 10;

            // nền mờ dễ đọc
            Composite old = g2.getComposite();
            g2.setComposite(AlphaComposite.SrcOver.derive(0.35f));
            g2.setColor(Color.BLACK);
            g2.fillRoundRect(x, y, w, h, 10, 10);
            g2.setComposite(old);

            g2.setColor(Color.WHITE);
            g2.drawString(text, x + pad, y + pad + fm.getAscent());
        }
    }

    // ----- Lớp đo FPS: đếm tick trong cửa sổ thời gian -----
    private static class FpsMeter {
        private final long windowNs;               // kích thước cửa sổ (ns)
        private final Deque<Long> timestamps = new ArrayDeque<>();

        FpsMeter(long windowNs) { this.windowNs = windowNs; }

        synchronized void onTick(long nowNs) {
            timestamps.addLast(nowNs);
            // bỏ các tick cũ ra khỏi cửa sổ
            long cutoff = nowNs - windowNs;
            while (!timestamps.isEmpty() && timestamps.peekFirst() < cutoff) {
                timestamps.removeFirst();
            }
        }

        synchronized double getFps() {
            if (timestamps.size() < 2) return 0.0;
            long first = timestamps.peekFirst();
            long last  = timestamps.peekLast();
            double spanSec = (last - first) / 1_000_000_000.0;
            if (spanSec <= 0) return 0.0;
            return (timestamps.size() - 1) / spanSec;
        }
    }
}
