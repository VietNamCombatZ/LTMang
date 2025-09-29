package buoi7_25_9;

import java.awt.Graphics;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;

import javax.imageio.ImageIO;
import javax.swing.JFrame;

public class ScreenClient extends JFrame {
    Socket soc;
    public static void main(String[] args) {
        new ScreenClient();
    }
    int off = 50;
    public ScreenClient() {
        this.setTitle("Share Screen");
        this.setSize(500, 400);
        this.setDefaultCloseOperation(3);
        try {
            soc = new Socket("localhost",2345);
        } catch(Exception e) {
            System.exit(1);
        }

        this.setVisible(true);
    }

    public void paint(Graphics g) {
        try {
            DataInputStream bis = new DataInputStream(soc.getInputStream());
            int n = bis.readInt();
            byte tmp[] = bis.readNBytes(n);


            ByteArrayInputStream bis1 = new ByteArrayInputStream(tmp);
            BufferedImage img1 = ImageIO.read(bis1);
            int w = this.getWidth()-2*off;
            int h = this.getHeight()-2*off;
            Image img2 = img1.getScaledInstance(w,h, Image.SCALE_SMOOTH);

            g.drawImage(img2, off, off, this.getWidth()-off, this.getHeight()-off,
                    0, 0, w,h, null);

            this.repaint();
        } catch (Exception e) {
        }
    }

}

