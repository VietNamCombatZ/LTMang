package buoi7_25_9;

import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.net.ServerSocket;
import java.net.Socket;

import javax.imageio.ImageIO;

public class ScreenServer {
    public static void main(String[] args) {
        new ScreenServer();
    }

    public ScreenServer() {
        Screen s = new Screen();
        //s.setPriority(10);
        s.start();
        try {
            ServerSocket server = new ServerSocket(2345);
            while (true) {
                Socket soc = server.accept();
                ScreenProcessing sp = new ScreenProcessing(soc);
                //sp.setPriority(1);
                sp.start();
            }
        } catch (Exception e) {

        }
    }

}

class Screen extends Thread{
    static byte[] tmp;
    static int count = 0;
    public void run() {
        Robot r = null;
        Rectangle capture = null;
        try {
            r = new Robot();
            capture = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
        }catch(Exception e) {

        }
        while (true) {
            try {
                BufferedImage img = r.createScreenCapture(capture);
                ByteArrayOutputStream bos = new ByteArrayOutputStream();
                ImageIO.write(img, "png", bos);
                bos.flush();
                tmp = bos.toByteArray();
                count++;
                if (count%100==0)
                    System.out.println(count+":"+tmp.length);
            }catch(Exception e) {

            }
        }
    }
}

class ScreenProcessing extends Thread{
    Socket soc;
    public ScreenProcessing(Socket soc) {
        this.soc = soc;
    }
    int countNow;
    public void run() {
        while (true) {
            try {
                if (countNow == Screen.count) {
                    Thread.sleep(1);
                    continue;
                }
                byte[] tmp = Screen.tmp.clone();
                countNow = Screen.count;
                //System.out.println("count:"+Screen.count);
                DataOutputStream output = new DataOutputStream(soc.getOutputStream());
                output.writeInt(tmp.length);
                output.write(tmp);
                output.flush();
                Thread.sleep(1);
            }catch(Exception e) {

            }
        }
    }
}

