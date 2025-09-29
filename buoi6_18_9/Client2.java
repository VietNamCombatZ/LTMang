package buoi6_18_9;

import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.net.UnknownHostException;

public class Client2 {

    private static final int SERVER_PORT = 9999;
    private static final String CLIENT_DOWNLOAD_DIRECTORY = "client_downloads/";

    public static void main(String[] args) {
        if (args.length < 2) {
            args = new String[]{
                    "192.168.56.1",   // host
                    "video.MOV"  // tên file trên server (có thể kèm thư mục: "data/video.mp4")
            };
            System.out.println("Vui lòng cung cấp đủ thông tin.");
            System.out.println("Cách dùng: java Client <địa_chỉ_ip_server> <tên_file>");
            System.out.println(args[0].toString() +" " + args[1].toString());
        }

        String serverAddress = args[0];
        String fileName = args[1];

        File directory = new File(CLIENT_DOWNLOAD_DIRECTORY);
        if (!directory.exists()) {
            directory.mkdir();
        }

        try (
                Socket socket = new Socket(serverAddress, SERVER_PORT);
                DataOutputStream dos = new DataOutputStream(socket.getOutputStream());
                DataInputStream dis = new DataInputStream(socket.getInputStream())) {
            System.out.println("Đã kết nối tới server: " + serverAddress);

            dos.writeUTF(fileName);
            dos.flush();
            System.out.println("Đã gửi yêu cầu tải file: " + fileName);

            long fileSize = dis.readLong();

            if (fileSize == -1) {
                System.err.println("Lỗi: File '" + fileName + "' không tồn tại trên server.");
                return;
            }

            System.out.println("Bắt đầu tải file. Kích thước: " + fileSize + " bytes.");

            String savePath = CLIENT_DOWNLOAD_DIRECTORY + fileName;
            try (FileOutputStream fos = new FileOutputStream(savePath);
                    BufferedOutputStream bos = new BufferedOutputStream(fos)) {

                byte[] buffer = new byte[8192];
                int bytesRead;
                long totalBytesRead = 0;

                while (totalBytesRead < fileSize && (bytesRead = dis.read(buffer, 0,
                        (int) Math.min(buffer.length, fileSize - totalBytesRead))) != -1) {
                    bos.write(buffer, 0, bytesRead);
                    totalBytesRead += bytesRead;

                    int percent = (int) ((totalBytesRead * 100) / fileSize);
                    System.out.print("Đang tải... " + percent + "%\r");
                }
                bos.flush();
            }
            System.out.println("\nTải file thành công! Đã lưu tại: " + savePath);

        } catch (UnknownHostException e) {
            System.err.println("Không thể tìm thấy server tại địa chỉ: " + serverAddress);
            System.err.println("Hãy chắc chắn rằng bạn đã nhập đúng địa chỉ IP và server đang chạy.");
        } catch (IOException e) {
            System.err.println("Lỗi kết nối hoặc I/O: " + e.getMessage());
            System.err.println("Hãy kiểm tra kết nối mạng và đảm bảo tường lửa không chặn cổng " + SERVER_PORT);
        }
    }
}