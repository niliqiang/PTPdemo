package com.example.niliqiang.ptpdemo;


import android.os.Handler;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.MulticastSocket;
import java.net.SocketException;
import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static android.content.ContentValues.TAG;


public class PTPSlave {
    private static float _rootDelayMax = 100.0F;
    private static float _rootDispersionMax = 100.0F;
    private static int _serverResponseDelayMax = 750;
    private static int _udpSocketTimeoutInMillis = 30000;
    private AtomicLong _cachedDeviceUptime = new AtomicLong();
    private AtomicLong _cachedPtpTime = new AtomicLong();
    private AtomicBoolean _ptpInitialized = new AtomicBoolean(false);




    public PTPSlave() {

    }

    boolean initialize(String ptpHost) throws IOException{
        if(requestTime(ptpHost, _rootDelayMax, _rootDispersionMax, _serverResponseDelayMax, _udpSocketTimeoutInMillis) != null) {
            return true;
        } else {
            return false;
        }
    }

    boolean wasInitialized() {
        return this._ptpInitialized.get();
    }

    long getCachedPtpTime() {
        return this._cachedPtpTime.get();
    }

    long getCachedDeviceUptime() {
        return this._cachedDeviceUptime.get();
    }

    public Date now() {
        long cachedPtpTime =  _getCachedPtpTime();
        long cachedDeviceUptime = _getCachedDeviceUptime();
        long deviceUptime = SystemClock.elapsedRealtime();
        long now = cachedPtpTime + (deviceUptime - cachedDeviceUptime);
        return new Date(now);
    }

    synchronized long[] requestTime(String ptpHost, float rootDelayMax, float rootDispersionMax, int serverResponseDelayMax, int timeoutInMillis) throws IOException {
        DatagramSocket socketEvent = null;
        DatagramSocket socketGeneral = null;
        byte[] bufferSync = new byte[44];
        byte[] bufferFollowUp = new byte[44];
        byte[] bufferDelayReq = new byte[44];
        byte[] bufferDelayResp = new byte[54];
        boolean receiveFollowUp = false;
        boolean receiveDelayResp = false;
        long[] t = new long[7];
        try {
            //IPv4封装PTP报文，EVENT消息的UDP目的标准端口号是319，General消息的UDP目的标准端口号是320。
            // 为了不ROOT手机也能接收PTP报文，将EVENT消息的端口号改为7319、General消息的端口号改为7320
            //EVENT：Sync、Delay_Req、Pdelay_Req
            //General：Follow_Up、Delay_Resp、Pdelay_Resp_Follow_Up、Announce、Signaling、Management
            InetAddress address = InetAddress.getByName(ptpHost);
            socketEvent = new DatagramSocket(7319);
            socketGeneral = new DatagramSocket(7320);
//            MulticastSocket multicastSocket = new MulticastSocket(7320);//创建组播套接字并绑定到端口（服务器）;端口号要大于1024，否则需要ROOT权限
//            InetAddress inetAddress = InetAddress.getByName("224.0.1.129");
//            multicastSocket.joinGroup(inetAddress);     //组播套接字加入组播组
            DatagramPacket packetSync = new DatagramPacket(bufferSync, bufferSync.length);//创建一个用于接收Sync数据的数据包
            DatagramPacket packetFollowUp = new DatagramPacket(bufferFollowUp, bufferFollowUp.length);//创建一个用于接收FollowUp数据的数据包
            DatagramPacket packetDelayReq = new DatagramPacket(bufferDelayReq, bufferDelayReq.length, address, 7319);//创建一个用于接收DelayReq数据的数据包
            DatagramPacket packetDelayResp = new DatagramPacket(bufferDelayResp, bufferDelayResp.length);//创建一个用于接收DelayResp数据的数据包
            while(true) {
                socketEvent.receive(packetSync);    //接收Sync数据包
                if(bufferSync[0] == 0x00) {
                    long receiveTime = System.currentTimeMillis();
                    long receiveTicks = SystemClock.elapsedRealtime();
                    t[2] = receiveTime;
                    while(!receiveFollowUp) {
                        socketGeneral.receive(packetFollowUp);  //接收FollowUp数据包
                        if((bufferFollowUp[0] == 0x08) && (bufferSync[31] == bufferFollowUp[31])) {
                            long preciseOriginTimestamp = readTimeStamp(bufferFollowUp, 36);
                            t[1] = preciseOriginTimestamp;
                            receiveFollowUp = true;
                        }
                    }
                    writePacketHead(bufferDelayReq);
                    long transmitTicks = SystemClock.elapsedRealtime();
                    long transmitTime = receiveTime + (transmitTicks - receiveTicks);
                    t[3] = transmitTime;
                    writeTimeStamp(bufferDelayReq, 36, transmitTime);
                    socketEvent.setSoTimeout(timeoutInMillis);
                    socketEvent.send(packetDelayReq);
                    while (!receiveDelayResp) {
                        socketGeneral.receive(packetDelayResp);
                        if((bufferDelayResp[0] == 0x09) && (bufferDelayResp[31] == (byte)0xAA)) {
                            long masterReceiveTime = readTimeStamp(bufferDelayResp, 36);
                            long lastReceiveTicks = SystemClock.elapsedRealtime();
                            long lastReceiveTime = receiveTime + (lastReceiveTicks - receiveTicks);
                            t[4] = masterReceiveTime;
                            t[5] = lastReceiveTicks;
                            t[6] = lastReceiveTime;
                            receiveDelayResp = true;
                        }
                    }
                    long offset = ((t[2] - t[1]) - (t[4] - t[3])) / 2L;
                    t[0] = offset;
                    this._ptpInitialized.set(true);
                    Log.i(TAG, "---- PTP successful response from " + ptpHost);
                    this.cachePtpTimeInfo(t);
                    break;
                }
            }
        }catch (Exception e) {
            Log.d(TAG, "---- PTP request time failed for " + ptpHost);
            e.printStackTrace();
            throw e;
        }
        return t;
    }

    void cachePtpTimeInfo(long[] timeInfo) {
        this._cachedPtpTime.set(this.ptpTime(timeInfo));
        this._cachedDeviceUptime.set(timeInfo[5]);
    }

    long ptpTime(long[] timeInfo) {
        long clockOffset = timeInfo[0];
        long lastReceiveTime = timeInfo[6];
        return lastReceiveTime - clockOffset;
    }

    private long _getCachedDeviceUptime() {
        if(wasInitialized()) {
            long cachedDeviceUptime = getCachedDeviceUptime();
            if(cachedDeviceUptime == 0L) {
                throw new RuntimeException("expected device time from last boot to be cached. couldn't find it.");
            } else {
                return cachedDeviceUptime;
            }
        } else {
            throw new RuntimeException("Sorry, PTP Slave not yet initialized.");
        }
    }

    private long _getCachedPtpTime() {
        if(wasInitialized()) {
            long cachedPtpTime = getCachedPtpTime();
            if(cachedPtpTime == 0L) {
                throw new RuntimeException("expected PTP time from last boot to be cached. couldn't find it.");
            } else {
                return cachedPtpTime;
            }
        } else {
            throw new RuntimeException("Sorry, PTP Slave not yet initialized.");
        }
    }

    private void writePacketHead(byte[] buffer) {
        buffer[0] = (byte) 0x81;    //messageId: Delay_Req Message (0x1)
        buffer[1] = 0x02;   // versionPTP: 2
        buffer[3] = 0x2C;   // messageLength: 44
        buffer[6] = 0x06;   // PTP_TWO_STEP: True; PTP_UNICAST: True
        buffer[29] = 0x01;   // SourcePortID: 1
        buffer[31] = (byte)0xAA;   // sequenceId: 0xAA
        buffer[32] = 0x01;   // control: Delay_Req Message (1)
        buffer[33] = (byte) 0xFF;   // logMessagePeriod: 127
    }

    private void writeTimeStamp(byte[] buffer, int offset, long time) {
        long seconds = time / 1000L;
        long milliseconds = time - seconds * 1000L;
        buffer[offset++] = (byte)((int)(seconds >> 24));
        buffer[offset++] = (byte)((int)(seconds >> 16));
        buffer[offset++] = (byte)((int)(seconds >> 8));
        buffer[offset++] = (byte)((int)(seconds >> 0));
        long fraction = milliseconds * 1000L;
        buffer[offset++] = (byte)((int)(fraction >> 24));
        buffer[offset++] = (byte)((int)(fraction >> 16));
        buffer[offset++] = (byte)((int)(fraction >> 8));
        buffer[offset++] = (byte)((int)(Math.random() * 255.0D));
    }

    private long readTimeStamp(byte[] buffer, int offset) {
        long seconds = this.readSeconds(buffer, offset);
        long fraction = this.readNanoSeconds(buffer, offset + 4);
        return seconds * 1000L + fraction / 1000000L;
    }

    private long readSeconds(byte[] buffer, int offset) {
        byte b0 = buffer[offset];
        byte b1 = buffer[offset + 1];
        byte b2 = buffer[offset + 2];
        byte b3 = buffer[offset + 3];
        return ((long)this.ui(b0) << 24) + ((long)this.ui(b1) << 16) + ((long)this.ui(b2) << 8) + (long)this.ui(b3);
    }

    private long readNanoSeconds(byte[] buffer, int offset) {
        byte b6 = buffer[offset];
        byte b7 = buffer[offset + 1];
        byte b8 = buffer[offset + 2];
        byte b9 = buffer[offset + 3];
        return ((long)this.ui(b6) << 24) + ((long)this.ui(b7) << 16) + ((long)this.ui(b8) << 8) + (long)this.ui(b9);
    }

    private int ui(byte b) {
        return b & 255;
    }


}
