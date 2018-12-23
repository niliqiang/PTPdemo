package com.example.niliqiang.ptpdemo;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.util.Log;
import android.widget.TextView;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;

import static android.content.ContentValues.TAG;

public class MainActivity extends AppCompatActivity {

    private TextView tv_ptpTime;
    private TextView tv_systemTime;
    PTPSlave PTP_Slave = new PTPSlave();
//    WifiManager.MulticastLock lock;
    public static final int MSG_ONE = 1;
    private Handler handlerInitPTP = new Handler(){
        @Override
        public void handleMessage(Message msg){
            switch (msg.what){
                case MSG_ONE:
                    new PTPTimeThread().start();
                    break;
                default:
                    break;
            }
        }
    };

    private Handler handlerPTPTime = new Handler(){
        @Override
        public void handleMessage(Message msg){
            switch (msg.what){
                case MSG_ONE:
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            mGetPTPTime();
                        }
                    }).start();
                    break;
                default:
                    break;
            }
        }
    };

    private Handler handlerSystemTime = new Handler(){
        @Override
        public void handleMessage(Message msg){
            switch (msg.what){
                case MSG_ONE:
                    new Thread(new Runnable() {
                        @Override
                        public void run() {
                            mGetSystemTime();
                        }
                    }).start();
                    break;
                default:
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initTextView();

        // 开启一个子线程，初始化PTP线程，等待有返回结果后开始通过PTP获取时间
        // google 不允许网络请求（HTTP、Socket）等相关操作直接在Main Thread类中进行
        new initPTPThread().start();
        new SystemTimeThread().start();

    }

    public class initPTPThread extends Thread {
        @Override
        public void run() {
//            allowMulticast();   //允许WIFI接收组播报文
            initPTPSlave();
//            lock.release();     //释放多播锁
            Message msgInitPTP = new Message();
            msgInitPTP.what = MSG_ONE;
            handlerInitPTP.sendMessage(msgInitPTP);
        }
    }

    public class PTPTimeThread extends Thread {
        @Override
        public void run() {
            do {
                try {
                    Thread.sleep(1000);
                    Message msgPTPTime = new Message();
                    msgPTPTime.what = MSG_ONE;
                    handlerPTPTime.sendMessage(msgPTPTime);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } while(true);
        }
    }

    public class SystemTimeThread extends Thread{
        @Override
        public void run(){
            super.run();
            do {
                try{
                    Thread.sleep(1000);
                    Message msgSystemTime = new Message();
                    msgSystemTime.what = MSG_ONE;
                    handlerSystemTime.sendMessage(msgSystemTime);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            } while(true);
        }
    }

    public void mGetPTPTime() {
        if(!PTP_Slave.wasInitialized()){
            return;
        }
        final Date datePTPTime = PTP_Slave.now();

        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tv_ptpTime.setText(dateToString(datePTPTime, "yyyy-MM-dd HH:mm:ss.SSS"));
            }
        });
    }

    public void mGetSystemTime() {
        final Date dateSystemTime = new Date(System.currentTimeMillis());
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                tv_systemTime.setText(dateToString(dateSystemTime, "yyyy-MM-dd HH:mm:ss.SSS"));
            }
        });
    }

    private void initPTPSlave() {
        try {
            PTP_Slave.initialize("10.112.35.226");

        } catch (Exception e){
            Log.d(TAG, "---- PTP_Slave initialize failed");
            e.printStackTrace();
        }
    }

    private void initTextView() {
        tv_ptpTime = findViewById(R.id.tv_ptp_time);
        tv_systemTime = findViewById(R.id.tv_system_time);
    }

    // formatType格式为yyyy-MM-dd HH:mm:ss.SSS（yyyy年MM月dd日 HH时mm分ss.SSS秒）
    // data Date类型的时间
    public String dateToString(Date data, String formatType) {
        return new SimpleDateFormat(formatType).format(data);
    }

    /**
     * 允许WiFi接收多播报文
     */
//    private void allowMulticast() {
//        WifiManager wifi = (WifiManager)getApplicationContext().getSystemService(Context.WIFI_SERVICE);
//        if(wifi != null) {
//            lock = wifi.createMulticastLock("multicastLock");
//            lock.acquire();
//        }
//    }
}
