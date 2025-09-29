package buoi4_22_8;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.PrintWriter;
import java.net.Socket;

public class ConnectServer {
    public static void main(String[] args){
        try{
            Socket soc = new Socket("192.168.10.62", 3000);
//            soc.setSoTimeout(1); //thêm vào sẽ gây bug -> vì sao
            System.out.println("Connected to server");
            String str = "Diem danh!! 102230153";
            DataInputStream dis = new DataInputStream(soc.getInputStream());
            System.out.println(dis.readUTF());

            DataOutputStream dos = new DataOutputStream(soc.getOutputStream());
            dos.writeUTF(str);   // gửi chuỗi theo chuẩn UTF
            dos.flush();


        }catch(Exception e){
            System.out.println("Error: " +e);
        }
    }
}



