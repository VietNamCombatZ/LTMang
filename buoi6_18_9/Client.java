package buoi6_18_9;
import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.net.URISyntaxException;

public class Client {

    private static final int STATUS_OK = 0;
    private static final int STATUS_NOT_FOUND = 1;
    private static final int STATUS_BAD_REQUEST = 2;
    private static final int STATUS_SERVER_ERROR = 3;

    public static void main(String[] args) {
        // Tham số mặc định khi bấm Run trong IntelliJ (bạn đổi tuỳ ý)
        if (args.length < 3) {
            args = new String[]{
                    "10.10.29.100",   // host
                    "5050",        // port
                    "huyTest01.txt"  // tên file trên server (có thể kèm thư mục: "data/video.mp4")
            };
            System.out.println("Không có tham số, dùng mặc định: " + String.join(" ", args));
        }

        String host = args[0];
        int port = Integer.parseInt(args[1]);
        String remoteName = args[2];

        // === CHỌN NƠI LƯU ===
        // Cách A: lưu ở Working Directory (mặc định của IntelliJ)
        Path baseDir = Paths.get(System.getProperty("user.dir"));

        // // Cách B (tuỳ chọn): lưu cạnh Client.class (thư mục biên dịch / target / out)
        // try {
        //     baseDir = Paths.get(Client.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getParent();
        // } catch (URISyntaxException e) {
        //     baseDir = Paths.get(System.getProperty("user.dir"));
        // }

        // Lấy đúng tên file cuối cùng như trên server (bỏ phần thư mục nếu có)
        String saveFileName = Paths.get(remoteName).getFileName().toString();
        Path localPath = ensureNonClobber(baseDir.resolve(saveFileName)); // tránh ghi đè nếu đã tồn tại

        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 10_000);
            socket.setTcpNoDelay(true);
            socket.setSoTimeout(0);

            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()));
                 DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()))) {

                // Gửi tên file cho server
                byte[] nameBytes = remoteName.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                out.writeInt(nameBytes.length);
                out.write(nameBytes);
                out.flush();

                // Nhận trạng thái
                int status = in.readInt();
                if (status != STATUS_OK) {
                    System.err.println(switch (status) {
                        case STATUS_NOT_FOUND -> "Server: KHÔNG TÌM THẤY FILE";
                        case STATUS_BAD_REQUEST -> "Server: YÊU CẦU KHÔNG HỢP LỆ";
                        case STATUS_SERVER_ERROR -> "Server: LỖI NỘI BỘ";
                        default -> "Server: TRẠNG THÁI KHÔNG XÁC ĐỊNH (" + status + ")";
                    });
                    return;
                }

                long size = in.readLong();
                if (localPath.getParent() != null) Files.createDirectories(localPath.getParent());
                System.out.printf("Đang tải \"%s\" (%,d bytes) -> %s%n",
                        remoteName, size, localPath.toAbsolutePath());

                try (OutputStream fout = new BufferedOutputStream(Files.newOutputStream(localPath))) {
                    copyFixedBytes(in, fout, size);
                }
                System.out.println("Tải xong!");
            }
        } catch (IOException e) {
            System.err.println("Lỗi client: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void copyFixedBytes(InputStream in, OutputStream out, long size) throws IOException {
        byte[] buf = new byte[1024 * 1024];
        long remaining = size;
        while (remaining > 0) {
            int toRead = (int) Math.min(buf.length, remaining);
            int n = in.read(buf, 0, toRead);
            if (n == -1) throw new EOFException("Luồng vào kết thúc sớm. Còn " + remaining + " bytes.");
            out.write(buf, 0, n);
            remaining -= n;
        }
        out.flush();
    }

    // Nếu file đã tồn tại, tự động đổi thành "name (1).ext", "name (2).ext", ...
    private static Path ensureNonClobber(Path p) {
        if (!Files.exists(p)) return p;
        String fileName = p.getFileName().toString();
        String base = fileName;
        String ext = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            ext  = fileName.substring(dot); // bao gồm dấu chấm
        }
        int i = 1;
        Path cand;
        do {
            cand = p.getParent().resolve(base + " (" + i + ")" + ext);
            i++;
        } while (Files.exists(cand));
        return cand;
    }
}

