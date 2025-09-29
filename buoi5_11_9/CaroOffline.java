package buoi5_11_9;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Point;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.util.Vector;

import javax.swing.JFrame;

public class CaroOffline extends JFrame implements MouseListener {
    public static void main(String[] args) {
        new CaroOffline();
    }

    int n = 15;
    int s = 40;
    int off = 50;
    Vector<Point> dadanh = new Vector<Point>();
    public CaroOffline() {
        this.setTitle("Game Caro");
        this.setSize(off * 2 + n * s, off * 2 + n * s);
        this.setDefaultCloseOperation(3);
        this.addMouseListener(this);

        this.setVisible(true);
    }

    public void paint(Graphics g) {
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, this.getWidth(), this.getHeight());

        g.setColor(Color.BLACK);
        for (int i = 0; i <= n; i++) {
            g.drawLine(off, off + i * s, off + s * n, off + i * s);
            g.drawLine(off + i * s, off, off + i * s, off + s * n);
        }
        g.setFont(new Font("arial",Font.BOLD,s));
        for (int i=0;i<dadanh.size();i++) {
            int ix = dadanh.get(i).x;
            int iy = dadanh.get(i).y;

            int x = off + ix*s + s - s/2 - s/4;
            int y = off + iy*s + s - s/2 + s/4;
            String str = "o";
            if (i%2==1) str ="x";
            g.drawString(str, x, y);
        }
    }

    @Override
    public void mouseClicked(MouseEvent e) {
        int x = e.getX();
        int y = e.getY();
        if (x < off || x >= off + s * n)
            return;
        if (y < off || y >= off + s * n)
            return;
        int ix = (x - off) / s;
        int iy = (y - off) / s;
        for (Point p:dadanh) {
            if (p.x==ix && p.y==iy) return;
        }
        dadanh.add(new Point(ix,iy));
        this.repaint();

    }

    @Override
    public void mousePressed(MouseEvent e) {
        // TODO Auto-generated method stub

    }

    @Override
    public void mouseReleased(MouseEvent e) {
        // TODO Auto-generated method stub

    }

    @Override
    public void mouseEntered(MouseEvent e) {
        // TODO Auto-generated method stub

    }

    @Override
    public void mouseExited(MouseEvent e) {
        // TODO Auto-generated method stub

    }

}
