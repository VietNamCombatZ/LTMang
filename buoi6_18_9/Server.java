package buoi6_18_9;

import java.io.*;
import java.net.*;
import java.nio.channels.*;
import java.nio.file.*;
import java.util.concurrent.*;

public class Server {

    // ====== CẦN CHỈNH ======
    private static final int PORT = 5050;
    // Thư mục chứa file để phục vụ tải
    private static final Path BASE_DIR = Paths.get("/Users/Huy/LTmang/buoi6_18_9/shared"); // vd: ./shared
    // Số luồng phục vụ tối đa
    private static final int MAX_THREADS = 200;
    // =======================

    // Giao thức: status: 0=OK, 1=NOT_FOUND, 2=BAD_REQUEST, 3=SERVER_ERROR
    private static final int STATUS_OK = 0;
    private static final int STATUS_NOT_FOUND = 1;
    private static final int STATUS_BAD_REQUEST = 2;
    private static final int STATUS_SERVER_ERROR = 3;

    public static void main(String[] args) {
        // Cho phép chỉnh port/base_dir qua args nếu muốn: java Server 5050 /path/to/shared
        int port = PORT;
        final Path base;
        if (args.length >= 1) {
            port = Integer.parseInt(args[0]);
        }
        if (args.length >= 2) {
            base = Paths.get(args[1]);
        } else {
            base = BASE_DIR;
        }

        if (!Files.isDirectory(base)) {
            System.err.println("BASE_DIR không tồn tại hoặc không phải thư mục: " + base.toAbsolutePath());
            System.exit(1);
        }

        ExecutorService pool = new ThreadPoolExecutor(
                Math.min(8, MAX_THREADS),         // core
                MAX_THREADS,                       // max
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new ThreadFactory() {
                    private final ThreadFactory def = Executors.defaultThreadFactory();
                    @Override public Thread newThread(Runnable r) {
                        Thread t = def.newThread(r);
                        t.setName("file-server-worker-" + t.getId());
                        t.setDaemon(false);
                        return t;
                    }
                },
                new ThreadPoolExecutor.CallerRunsPolicy() // khi quá tải, thread accept tạm xử lý
        );

        try (ServerSocket serverSocket = new ServerSocket()) {
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(port));
            System.out.println("Server lắng nghe tại port " + port + ", base dir: " + base.toAbsolutePath());

            while (true) {
                Socket client = serverSocket.accept();
                client.setTcpNoDelay(true);
                client.setSoTimeout(0); // không timeout do file lớn
                pool.execute(() -> handleClient(client, base));
            }
        } catch (IOException e) {
            System.err.println("Lỗi server: " + e.getMessage());
            e.printStackTrace();
        } finally {
            pool.shutdown();
        }
    }

    private static void handleClient(Socket socket, Path baseDir) {
        String remote = socket.getRemoteSocketAddress().toString();
        try (socket;
             DataInputStream in = new DataInputStream(new BufferedInputStream(socket.getInputStream()));
             DataOutputStream out = new DataOutputStream(new BufferedOutputStream(socket.getOutputStream()))
        ) {
            // Đọc độ dài tên file (bytes UTF-8) + tên file (không vượt quá 4096 bytes)
            int nameLen = in.readInt();
            if (nameLen <= 0 || nameLen > 4096) {
                sendStatus(out, STATUS_BAD_REQUEST);
                return;
            }
            byte[] nameBytes = new byte[nameLen];
            in.readFully(nameBytes);
            String requestedName = new String(nameBytes, java.nio.charset.StandardCharsets.UTF_8);

            // Chuẩn hóa & chống path traversal
            Path resolved = safeResolve(baseDir, requestedName);
            if (resolved == null) {
                System.out.printf("[%s] BAD_REQUEST: path traversal: %s%n", remote, requestedName);
                sendStatus(out, STATUS_BAD_REQUEST);
                return;
            }

            if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
                System.out.printf("[%s] NOT_FOUND: %s%n", remote, requestedName);
                sendStatus(out, STATUS_NOT_FOUND);
                return;
            }

            long size = Files.size(resolved);
            // Gửi header: OK + size
            out.writeInt(STATUS_OK);
            out.writeLong(size);
            out.flush();

            // Stream file -> socket (FileChannel.transferTo cho hiệu năng tốt)
            try (FileChannel fc = FileChannel.open(resolved, StandardOpenOption.READ);
                 WritableByteChannel wch = Channels.newChannel(socket.getOutputStream())) {
                long pos = 0;
                while (pos < size) {
                    long sent = fc.transferTo(pos, size - pos, wch);
                    if (sent <= 0) break; // đề phòng
                    pos += sent;
                }
            }

            System.out.printf("[%s] ĐÃ GỬI: %s (%,d bytes)%n", remote, requestedName, size);

        } catch (EOFException eof) {
            System.out.printf("[%s] Client đóng kết nối sớm%n", remote);
        } catch (IOException e) {
            System.out.printf("[%s] Lỗi khi phục vụ: %s%n", remote, e.getMessage());
        }
    }

    private static Path safeResolve(Path baseDir, String filename) {
        // Không cho phép null/empty hoặc chứa ký tự không hợp lệ cơ bản
        if (filename == null || filename.isBlank()) return null;
        // Loại bỏ dấu '\' về '/'
        filename = filename.replace('\\', '/');
        // Không cho phép đường dẫn tuyệt đối
        if (filename.startsWith("/") || filename.startsWith("~")) return null;

        Path candidate = baseDir.resolve(filename).normalize();
        if (!candidate.startsWith(baseDir.normalize())) {
            return null; // path traversal
        }
        return candidate;
    }

    private static void sendStatus(DataOutputStream out, int status) throws IOException {
        out.writeInt(status);
        out.flush();
    }
}

