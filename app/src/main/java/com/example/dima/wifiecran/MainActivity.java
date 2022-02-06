package com.example.dima.wifiecran;

import android.media.MediaCodec;
import android.media.MediaCodec.BufferInfo;
import android.media.MediaFormat;
import android.os.Bundle;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.Window;
import android.view.WindowManager;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayDeque;
import java.util.Arrays;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {

    private final Object o3=new Object();
    private ArrayDeque<byte[]> listPacketFr = new ArrayDeque<>();

    private PlayerThread mPlayer = null;
    private WorkerNetReceive nr=null;
    private WorkerObrFrame wof=null;
    private TCPClient nw=null;

    public static class ByteUtils {
        public static byte[] longToBytes(long x) {
            ByteBuffer buffer = ByteBuffer.allocate(8);
            buffer.putLong(0, x);
            return buffer.array();
        }
        public static long bytesToLong(byte[] bytes,int n) {
            ByteBuffer buffer = ByteBuffer.allocate(8);
            buffer.order(ByteOrder.LITTLE_ENDIAN);
            buffer.put(bytes, n-8, 8);
            buffer.flip();//need flip
            return buffer.getLong();
        }
    }

    int bytesToInt(byte[]b,int n){
        return ((b[n - 4] & 0xFF) << 24) + ((b[n - 3] & 0xFF) << 16) + ((b[n - 2] & 0xFF) << 8) + (b[n - 1] & 0xFF);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);

        SurfaceView sv = new SurfaceView(this);
        sv.getHolder().addCallback(this);
        setContentView(sv);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {

    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mPlayer == null) {
            mPlayer = new PlayerThread(holder.getSurface());
            mPlayer.start();
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mPlayer != null) {
            mPlayer.mRunning=false;
            mPlayer=null;
        }
        if(nr!=null){
            nr.udpSocket.close();
            nr.mRunning=false;
            nr=null;
        }
    }

    private class PlayerThread extends Thread {
        boolean mConfigured=false;
        MediaCodec mDecoder;
        boolean mRunning=true;
        long mTimeoutUsec = 5000000;
        Surface surface;

        PlayerThread(Surface surface) {
            this.surface=surface;
        }

        String TAG="extractHevcParamSets";

        private ByteBuffer extractHevcParamSets(byte[] bitstream) {
            final byte[] startCode = {0x00, 0x00, 0x00, 0x01};
            int nalBeginPos = 0, nalEndPos = 0;
            int nalUnitType = -1;
            int nlz = 0;
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            for (int pos = 0; pos < bitstream.length; pos++) {
                if (2 <= nlz && bitstream[pos] == 0x01) {
                    nalEndPos = pos - nlz;
                    if (nalUnitType == 32 || nalUnitType == 33 || nalUnitType == 34) {
                        // extract VPS(32), SPS(33), PPS(34)
                        Log.d(TAG, "NUT=" + nalUnitType + " range={" + nalBeginPos + "," + nalEndPos + "}");
                        try {
                            baos.write(startCode);
                            baos.write(bitstream, nalBeginPos, nalEndPos - nalBeginPos);
                        } catch (IOException ex) {
                            Log.e(TAG, "extractHevcParamSets", ex);
                            return null;
                        }
                    }
                    nalBeginPos = ++pos;
                    nalUnitType = (bitstream[pos] >> 1) & 0x2f;
                    if (0 <= nalUnitType && nalUnitType <= 31) {
                        break;  // VCL NAL; no more VPS/SPS/PPS
                    }
                }
                nlz = (bitstream[pos] != 0x00) ? 0 : nlz + 1;
            }
            return ByteBuffer.wrap(baos.toByteArray());
        }

        void configure(byte[] bitstream) {
            if (!mConfigured) { // просто флаг, чтобы знать, что декодер готов
                // создаем видео формат
                MediaFormat format = MediaFormat.createVideoFormat("video/hevc", 16, 16);
                //format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, bitstream.length);
                format.setByteBuffer("csd-0", extractHevcParamSets(bitstream));
                // создаем декодер
                try {
                    mDecoder = MediaCodec.createDecoderByType("video/hevc");
                } catch (IOException e) {
                    e.printStackTrace();
                }

                // конфигурируем декодер
                mDecoder.configure(format, surface, null, 0);
                mDecoder.start();
                if (mDecoder == null) {
                    Log.e("DecodeActivity", "Can't find video info!");
                    return;
                }
                mConfigured = true;
            }
        }

        void decodeSample(byte[] data, int flags) {
            if(mConfigured) {
                int index;
                index = mDecoder.dequeueInputBuffer(-1);
                if (index >= 0) {
                    ByteBuffer buffer;
                    buffer = mDecoder.getInputBuffers()[index];
                    int size=data.length;
                    buffer.put(data);
                    // сообщаем системе о доступности буфера данных
                    mDecoder.queueInputBuffer(index, 0, size, 1, flags);
                }
            }
        }

        void release() {
            if (mConfigured) {
                mDecoder.stop();
                mDecoder.release();
                mConfigured = false;
            }
        }

        @Override
        public void run() {
            try {
                BufferInfo info = new BufferInfo(); // переиспользуем BufferInfo
                if(nr==null){
                    nr=new WorkerNetReceive();
                    new Thread(nr).start();
                }
                while (mRunning) {
                    if (mConfigured) { // если кодек готов
                        int index = mDecoder.dequeueOutputBuffer(info, mTimeoutUsec);
                        if (index >= 0) { // буфер с индексом index доступен
                            // info.size > 0: если буфер не нулевого размера, то рендерим на Surface
                            mDecoder.releaseOutputBuffer(index, info.size > 0); //info.size > 0
                            // заканчиваем работу декодера если достигнут конец потока данных
                            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) == MediaCodec.BUFFER_FLAG_END_OF_STREAM) {
                                mRunning = false;
                                break;
                            }
                        }
                    } else {
                        // просто спим, т.к. кодек не готов
                        Thread.sleep(100);
                    }
                }
            } catch (InterruptedException e) {
                e.printStackTrace();
            } finally {
                // освобождение ресурсов
                release();
            }
        }
    }

    private class WorkerObrFrame implements Runnable {

        boolean mRunning = true;

        @Override
        public void run() {
            int flags;
            if(nw==null){
                nw=new TCPClient();
                new Thread(nw).start();
            }
            while (mRunning) {
                boolean ns;
                synchronized (o3) {
                    ns=listPacketFr.isEmpty();
                }
                if(!ns){
                    byte[] message;
                    synchronized (o3) {
                        message = listPacketFr.poll();
                    }
                    if(message!=null) {
                        int n = message.length;
                        int prov = bytesToInt(message, 4);
                        flags = bytesToInt(message, n);
                        byte[] frame = Arrays.copyOfRange(message, 4, n - 4);
                        if (prov == 3) {
                            if (mPlayer != null)
                                mPlayer.configure(frame);
                        }
                        if (mPlayer != null)
                            mPlayer.decodeSample(frame, flags);
                    }
                }
                else {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                }
            }
        }
    }

    private InetAddress serverAddr;

    class TCPClient implements Runnable {
        Socket socket;
        private int SERVER_PORT = 4322;
        private boolean mRunning = false;
        private DataOutputStream mBufferOut;
        private DataInputStream mBufferIn;

        void stop(){
            ByteBuffer bb = ByteBuffer.allocate(8);
            bb.putInt(1);
            bb.putInt(1);
            byte[] sendData = bb.array();
            try {
                mBufferOut.write(sendData);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        public void run() {
            mRunning = true;
            try {
                Log.d("TCP Client", "C: Connecting...");
                socket = new Socket(serverAddr, SERVER_PORT);
                mBufferOut = new DataOutputStream(socket.getOutputStream());
                mBufferIn =  new DataInputStream(socket.getInputStream());
                int siz = mBufferIn.readInt();
                while (mRunning) {
                    byte[] arr=new byte[siz];
                    mBufferIn.readFully(arr);
                    synchronized (o3) {
                        listPacketFr.addLast(arr);
                    }
                    siz = mBufferIn.readInt();
                }
            } catch (IOException e) {
                Log.e("TCP", "C: Error", e);
            }
            finally {
                synchronized (o3) {
                    listPacketFr.clear();
                }
                if(wof!=null){
                    wof.mRunning=false;
                    wof=null;
                }
            }
        }
    }

    private class WorkerNetReceive implements Runnable {
        int port=1234;
        DatagramSocket udpSocket;
        boolean mRunning=true;

        void send() throws IOException {
            ByteBuffer bb = ByteBuffer.allocate(4);
            bb.putInt(0);
            byte[] sendData = bb.array();
            InetAddress local = InetAddress.getByName("255.255.255.255");
            DatagramPacket sendPacket = new DatagramPacket(
                    sendData, sendData.length, local, 4321);
            udpSocket.send(sendPacket);
        }

        @Override
        public void run() {
            try {
                udpSocket = new DatagramSocket(port);
                udpSocket.setReceiveBufferSize(4194304);
                udpSocket.setBroadcast(true);
                send();
                byte[] message = new byte[100];
                while (mRunning) {
                    DatagramPacket packet = new DatagramPacket(message,message.length);
                    udpSocket.receive(packet);
                    serverAddr = packet.getAddress();
                    if(wof==null){
                        wof=new WorkerObrFrame();
                        new Thread(wof).start();
                    }
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            finally {
                udpSocket.close();
                if(nw!=null){
                    nw.stop();
                    nw.mRunning=false;
                    try {
                        nw.socket.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    nw=null;
                }
            }
        }
    }
}