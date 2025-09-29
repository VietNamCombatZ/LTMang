package buoi4_22_8;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Date;

public class DataTimeServer {
    public static void main(String[] args){
        try{
            ServerSocket server = new ServerSocket(5000);
            while(true){
                Socket soc = server.accept();
                System.out.println(soc.getInetAddress().getHostAddress());
//                DataInputStream dis = new DataInputStream(soc.getInputStream());
//                String str = dis.readUTF();
                DataOutputStream dos = new DataOutputStream(soc.getOutputStream());
                dos.writeUTF("bay gio la: " + new Date().toString());
//                System.out.println(str);

            }
        }catch(Exception e){
            System.out.println("Hello World");
        }
    }
}

class xuly extends Thread{
    Socket soc;
    public xuly(Socket soc){
        this.soc = soc;
    }
    public void run(){

    }
}


