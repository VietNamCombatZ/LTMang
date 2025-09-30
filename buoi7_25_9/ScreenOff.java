package buoi7_25_9;

import java.awt.Graphics;
import java.awt.Image;
import java.awt.Rectangle;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

import javax.imageio.ImageIO;
import javax.swing.JFrame;

public class ScreenOff extends JFrame {

    public static void main(String[] args) {
        new ScreenOff();
    }
    int off = 50;
//    int off=0;
    public ScreenOff() {
        this.setTitle("Share Screen");
        this.setSize(500, 400);
        this.setDefaultCloseOperation(3);

        this.setVisible(true);
    }

    public void paint(Graphics g) {
        try {
            Robot r = new Robot();
            Rectangle capture = new Rectangle(Toolkit.getDefaultToolkit().getScreenSize());
            BufferedImage img = r.createScreenCapture(capture);

            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            ImageIO.write(img, "png", bos);
            bos.flush();
            byte[] tmp = bos.toByteArray();

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

