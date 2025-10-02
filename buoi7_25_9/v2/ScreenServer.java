package buoi7_25_9.v2;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicReference;
public class ScreenServer {
    private static final int BLOCK_SIZE = 16;
    private static final float FULL_FRAME_THRESHOLD = 0.35f;
    private final AtomicReference<ScreenFrame> latestFrame = new AtomicReference<>();
    private final ArrayList<ClientHandler> clients = new ArrayList<>();
    public static void main(String[] args) throws Exception {
        new ScreenServer().start();
    }
    public void start() throws Exception {
        System.out.println("khoi dong thanh cong");
        new Thread(new CaptureTask()).start();
        ServerSocket server = new ServerSocket(2345);
        while (true) {
            Socket clientSocket = server.accept();
            ClientHandler handler = new ClientHandler(clientSocket);
            clients.add(handler);
            new Thread(handler).start();
            System.out.println("co ket noi moi, tong so: " + clients.size());
        }
    }
    static class ScreenFrame {
        final BufferedImage rawImage;
        final int sequence;
        ScreenFrame(BufferedImage rawImage, int sequence) {
            this.rawImage = rawImage;
            this.sequence = sequence;
        }
    }
    class CaptureTask implements Runnable {
        private int sequence = 0;
        public void run() {
            try {
                Robot robot = new Robot();
                Rectangle screenRect = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
                while (true) {
                    BufferedImage screen = robot.createScreenCapture(screenRect);
                    latestFrame.set(new ScreenFrame(screen, ++sequence));
                    Thread.sleep(50);
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }
    class ClientHandler implements Runnable {
        private final Socket socket;
        private float quality = 0.7f;
        private int lastSentSeq = -1;
        private BufferedImage lastSentImage = null;
        public ClientHandler(Socket socket) {
            this.socket = socket;
        }
        public void run() {
            try (DataOutputStream out = new DataOutputStream(socket.getOutputStream());
                 DataInputStream in = new DataInputStream(socket.getInputStream())) {
                System.out.println("ip: " + socket.getInetAddress());
                ScreenFrame firstFrame;
                while ((firstFrame = latestFrame.get()) == null) {
                    Thread.sleep(100);
                }
                out.writeInt(firstFrame.rawImage.getWidth());
                out.writeInt(firstFrame.rawImage.getHeight());
                sendFullFrame(out, firstFrame);
                while (!socket.isClosed()) {
                    if (in.available() > 0) {
                        String command = in.readUTF();
                        if (command.startsWith("QUALITY:")) {
                            quality = Float.parseFloat(command.substring(8));
                            quality = Math.max(0.1f, Math.min(1.0f, quality));
                            System.out.println("Client " + socket.getInetAddress() + " thay doi chat luong: " +
                                    (int) (quality * 100) + "%");
                        }
                    }
                    ScreenFrame currentFrame = latestFrame.get();
                    if (currentFrame != null && currentFrame.sequence > lastSentSeq) {
                        Rectangle changeBox = findChangeBoundingBox(lastSentImage,
                                currentFrame.rawImage);
                        if (changeBox != null) {
                            float changedAreaRatio = (float) (changeBox.width * changeBox.height) /
                                    (lastSentImage.getWidth() * lastSentImage.getHeight());
                            if (changedAreaRatio > FULL_FRAME_THRESHOLD) {
                                sendFullFrame(out, currentFrame);
                            } else {
                                sendDeltaFrame(out, currentFrame, changeBox);
                            }
                        }
// Nếu không có gì thay đổi (changeBox == null), không gửi gì cả.
                    }
// Nghỉ một chút để giảm tải CPU.
                    Thread.sleep(30);
                }
            } catch (Exception e) {
                System.out.println("disconnect, ip: " + socket.getInetAddress());
            } finally {
                clients.remove(this);
                try {
                    socket.close();
                } catch (IOException e) { /* Bỏ qua */ }
                System.out.println("tong so client: " + clients.size());
            }
        }
        private void sendFullFrame(DataOutputStream out, ScreenFrame frame) throws IOException {
            byte[] compressedData = compressImage(frame.rawImage, quality);
            out.writeBoolean(true); // isFullFrame = true
            out.writeInt(frame.sequence);
            out.writeInt(compressedData.length);
            out.write(compressedData);
            out.flush();
            this.lastSentImage = frame.rawImage;
            this.lastSentSeq = frame.sequence;
            if (frame.sequence % 30 == 0) {
                System.out.println("FULL " + frame.sequence + " (" + compressedData.length / 1024 + " KB) cho " + socket.getInetAddress());
            }
        }
        private void sendDeltaFrame(DataOutputStream out, ScreenFrame frame, Rectangle rect) throws
                IOException {
            BufferedImage deltaImage = frame.rawImage.getSubimage(rect.x, rect.y, rect.width,
                    rect.height);
            byte[] compressedData = compressImage(deltaImage, quality);
            out.writeBoolean(false); // isFullFrame = false
            out.writeInt(frame.sequence);
            out.writeInt(rect.x);
            out.writeInt(rect.y);
            out.writeInt(rect.width);
            out.writeInt(rect.height);
            out.writeInt(compressedData.length);
            out.write(compressedData);
            out.flush();
            this.lastSentImage = frame.rawImage;
            this.lastSentSeq = frame.sequence;
            if (frame.sequence % 30 == 0) {
                System.out.println("DELTA " + frame.sequence + " (" + compressedData.length / 1024 + " KB) cho " + socket.getInetAddress());
            }
        }
    }
    /**
     * Nén một ảnh BufferedImage thành mảng byte JPEG với chất lượng cho trước.
     */
    private byte[] compressImage(BufferedImage image, float quality) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(baos)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(image, null, null), param);
        } finally {
            writer.dispose();
        }
        return baos.toByteArray();
    }
    private Rectangle findChangeBoundingBox(BufferedImage oldImg, BufferedImage newImg) {
        if (oldImg == null) {
            return new Rectangle(newImg.getWidth(), newImg.getHeight());
        }
        int width = newImg.getWidth();
        int height = newImg.getHeight();
        int minX = width, minY = height, maxX = 0, maxY = 0;
        boolean changed = false;
        for (int y = 0; y < height; y += BLOCK_SIZE) {
            for (int x = 0; x < width; x += BLOCK_SIZE) {
                if (!isBlockSame(oldImg, newImg, x, y)) {
                    if (x < minX) minX = x;
                    if (y < minY) minY = y;
                    if (x + BLOCK_SIZE > maxX) maxX = x + BLOCK_SIZE;
                    if (y + BLOCK_SIZE > maxY) maxY = y + BLOCK_SIZE;
                    changed = true;
                }
            }
        }
        if (changed) {
            maxX = Math.min(width, maxX);
            maxY = Math.min(height, maxY);
            return new Rectangle(minX, minY, maxX - minX, maxY - minY);
        }
        return null;
    }
    private boolean isBlockSame(BufferedImage oldImg, BufferedImage newImg, int x, int y) {
        int endX = Math.min(x + BLOCK_SIZE, newImg.getWidth());
        int endY = Math.min(y + BLOCK_SIZE, newImg.getHeight());
        for (int j = y; j < endY; j++) {
            for (int i = x; i < endX; i++) {
                if (oldImg.getRGB(i, j) != newImg.getRGB(i, j)) {
                    return false; // Tìm thấy pixel khác nhau.
                }
            }
        }
        return true;
    }
}
