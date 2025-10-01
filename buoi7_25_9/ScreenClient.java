package buoi7_25_9;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;

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

    private void receiveLoop() {
        try {
            socket = new Socket(HOST, PORT);
            in = new DataInputStream(socket.getInputStream());

            while (!socket.isClosed()) {
                // 1) Đọc độ dài khung
                int n;
                try {
                    n = in.readInt();
                } catch (IOException ex) {
                    break; // socket đóng/ lỗi: thoát vòng lặp
                }
                if (n <= 0 || n > (50 * 1024 * 1024)) { // chặn khung bất thường
                    throw new IOException("Invalid frame size: " + n);
                }

                // 2) Đọc đủ n byte (readFully đảm bảo đủ)
                byte[] buf = new byte[n];
                in.readFully(buf);

                // 3) Giải mã ảnh (PNG từ server hiện tại)
                BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(buf));
                if (img != null) {
                    latestFrame = img;   // cập nhật frame mới
                    panel.repaint();     // chỉ yêu cầu vẽ khi có frame mới
                }
            }
        } catch (Exception e) {
            // Thông báo gọn khi mất kết nối
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

    /** Panel chỉ phụ trách VẼ từ latestFrame (không làm I/O). */
    private class VideoPanel extends JPanel {
        private static final int MARGIN = 20;

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            BufferedImage img = latestFrame;
            if (img == null) {
                // vẽ placeholder nhẹ
                Graphics2D g2 = (Graphics2D) g;
                g2.setFont(getFont().deriveFont(Font.PLAIN, 14f));
                String msg = "Đang chờ khung hình từ server...";
                FontMetrics fm = g2.getFontMetrics();
                int x = (getWidth() - fm.stringWidth(msg)) / 2;
                int y = (getHeight() - fm.getHeight()) / 2 + fm.getAscent();
                g2.drawString(msg, x, y);
                return;
            }

            int availW = Math.max(1, getWidth() - 2 * MARGIN);
            int availH = Math.max(1, getHeight() - 2 * MARGIN);

            // Giữ tỉ lệ
            double sx = availW / (double) img.getWidth();
            double sy = availH / (double) img.getHeight();
            double s = Math.min(sx, sy);

            int drawW = (int) Math.round(img.getWidth() * s);
            int drawH = (int) Math.round(img.getHeight() * s);
            int x = (getWidth() - drawW) / 2;
            int y = (getHeight() - drawH) / 2;

            Graphics2D g2 = (Graphics2D) g.create();
            // Ưu tiên tốc độ khi cần (có thể bật QUALITY nếu bạn muốn đẹp hơn)
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g2.drawImage(img, x, y, drawW, drawH, null);
            g2.dispose();
        }
    }
}
