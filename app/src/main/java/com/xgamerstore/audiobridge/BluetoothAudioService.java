package com.xgamerstore.audiobridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Build;
import android.os.IBinder;
import android.os.Process;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Secure Bluetooth Classic RFCOMM transport for peer AudioBridge apps only, NOT A2DP. */
public final class BluetoothAudioService extends Service {
    public static final String SEND="com.xgamerstore.audiobridge.PEER_SEND";
    public static final String RECEIVE="com.xgamerstore.audiobridge.PEER_RECEIVE";
    public static final String STOP="com.xgamerstore.audiobridge.PEER_STOP";
    private static final UUID AUDIO_UUID=UUID.fromString("3ec276c7-47e8-4e48-a854-c76ac4c30542");
    private static final int MAGIC=0x41423131;
    private static final int RATE=22050;
    private static final String CHANNEL="bridge-peer";
    public static volatile boolean running=false;
    public static volatile String status="Připraveno k připojení";
    private final AtomicBoolean go=new AtomicBoolean(false);
    private volatile BluetoothServerSocket server;
    private volatile BluetoothSocket socket;
    private volatile AudioRecord microphone;
    private volatile AudioTrack player;
    private AudioManager audio;

    @Override public void onCreate(){
        super.onCreate();
        audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(
                new NotificationChannel(CHANNEL,"Bluetooth AudioBridge",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent,int flags,int id){
        if(intent==null)return START_NOT_STICKY;
        if(STOP.equals(intent.getAction())){stopSession();stopSelf();return START_NOT_STICKY;}
        final boolean tx=SEND.equals(intent.getAction());
        if(!tx&&!RECEIVE.equals(intent.getAction()))return START_NOT_STICKY;
        if(!go.compareAndSet(false,true)){status="Spojení již běží";return START_NOT_STICKY;}
        if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){
            status="Chybí povolení k Bluetooth";go.set(false);stopSelf();return START_NOT_STICKY;
        }
        if(tx&&checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            status="Chybí povolení k mikrofonu";go.set(false);stopSelf();return START_NOT_STICKY;
        }
        Intent click=new Intent(this,BridgeActivity.class);
        PendingIntent open=PendingIntent.getActivity(this,10,click,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,11,new Intent(this,BluetoothAudioService.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification=new Notification.Builder(this,CHANNEL)
                .setSmallIcon(android.R.drawable.ic_media_play)
                .setContentTitle(tx?"AudioBridge – odesílání":"AudioBridge – Bluetooth příjem")
                .setContentText(tx?"Mikrofon → Bluetooth":"Bluetooth → kabelová sluchátka")
                .setContentIntent(open).setOngoing(true)
                .addAction(new Notification.Action.Builder(android.R.drawable.ic_media_pause,"Zastavit",stop).build())
                .build();
        try{
            if(Build.VERSION.SDK_INT>=34){
                startForeground(602,notification,tx?
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE:
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
            }else if(Build.VERSION.SDK_INT>=29&&tx)
                startForeground(602,notification,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(602,notification);
        }catch(RuntimeException e){
            status="Android odmítl spuštění: "+e.getMessage();
            go.set(false);stopSelf();return START_NOT_STICKY;
        }
        final int output=intent.getIntExtra("output",-1);
        final String peer=intent.getStringExtra("peer");
        running=true;
        status=tx?"Připojuji se k přijímači…":"Čekám na odesílající telefon…";
        new Thread(()->{
            try{if(tx)transmit(peer);else receive(output);}
            catch(Exception e){if(go.get())status="Chyba spojení: "+e.getMessage();}
            finally{
                boolean stopped=!go.get();
                go.set(false);running=false;clean();
                if(stopped)status="Přenos zastaven";
                else if(!status.startsWith("Chyba")&&!status.startsWith("Android")&&!status.startsWith("Zařízení"))
                    status="Přenos dokončen";
                stopForeground(STOP_FOREGROUND_REMOVE);
                stopSelf();
            }
        },"AudioBridge-Bluetooth").start();
        return START_NOT_STICKY;
    }
    private AudioDeviceInfo outputDevice(int id){
        for(AudioDeviceInfo device:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))
            if(device.getId()==id)return device;
        return null;
    }
    private static AudioFormat mono(){
        return new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
    }
    private static AudioFormat playback(){
        return new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(RATE).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
    }
    private BluetoothAdapter bt()throws IOException{
        BluetoothAdapter a=BluetoothAdapter.getDefaultAdapter();
        if(a==null||!a.isEnabled())throw new IOException("Bluetooth není zapnuté");
        return a;
    }
    private void receive(int output)throws Exception{
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        BluetoothAdapter adapter=bt();
        AudioDeviceInfo chosen=outputDevice(output);
        if(chosen==null)throw new IOException("Zařízení sluchátek bylo odpojeno");
        server=adapter.listenUsingRfcommWithServiceRecord("AudioBridge Peer Audio",AUDIO_UUID);
        BluetoothSocket accepted=server.accept();
        socket=accepted;
        closeServer();
        if(!go.get())return;
        status="Bluetooth připojeno, čekám na zvuk…";
        DataInputStream in=new DataInputStream(accepted.getInputStream());
        if(in.readInt()!=MAGIC)throw new IOException("Nekompatibilní vysílač");
        if(in.readInt()!=RATE)throw new IOException("Nesouhlasí vzorkovací frekvence");
        int min=AudioTrack.getMinBufferSize(RATE,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(min<=0)throw new IOException("Nepodporovaný zvukový výstup");
        AudioTrack track=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                .setAudioFormat(playback()).setBufferSizeInBytes(Math.max(min*2,8192))
                .setTransferMode(AudioTrack.MODE_STREAM).build();
        player=track;
        if(track.getState()!=AudioTrack.STATE_INITIALIZED)throw new IOException("AudioTrack nelze otevřít");
        if(!track.setPreferredDevice(chosen))throw new IOException("Android odmítl vybraný audio výstup");
        track.play();
        status="Přijímám Bluetooth zvuk → sluchátka";
        byte[] pcm=new byte[4096];
        int packets=0;
        while(go.get()){
            int len;
            try{len=in.readInt();}catch(IOException end){if(go.get())throw end;break;}
            if(len<=0||len>pcm.length||((len&1)!=0))throw new IOException("Poškozený zvukový paket");
            in.readFully(pcm,0,len);
            int pos=0;
            while(go.get()&&pos<len){
                int wrote=track.write(pcm,pos,len-pos,AudioTrack.WRITE_BLOCKING);
                if(wrote<=0)throw new IOException("Přehrávání selhalo: "+wrote);
                pos+=wrote;
            }
            if((++packets%100)==0){
                AudioDeviceInfo routed=track.getRoutedDevice();
                if(outputDevice(output)==null)throw new IOException("Zařízení sluchátek bylo odpojeno");
                if(routed!=null&&routed.getId()!=output)
                    status="Pozor: Android přesměroval zvuk na jiný výstup";
            }
        }
    }
    private void transmit(String peer)throws Exception{
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        if(peer==null)throw new IOException("Nevybraný přijímač");
        BluetoothAdapter adapter=bt();
        BluetoothDevice target=adapter.getRemoteDevice(peer);
        socket=target.createRfcommSocketToServiceRecord(AUDIO_UUID);
        adapter.cancelDiscovery();
        socket.connect();
        if(!go.get())return;
        status="Bluetooth připojeno, spouštím mikrofon…";
        int min=AudioRecord.getMinBufferSize(RATE,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
        if(min<=0)throw new IOException("Mikrofon nepodporuje požadovaný formát");
        AudioRecord record=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(mono()).setBufferSizeInBytes(Math.max(min*2,8192)).build();
        microphone=record;
        if(record.getState()!=AudioRecord.STATE_INITIALIZED)throw new IOException("Mikrofon nelze spustit");
        record.startRecording();
        if(record.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING)throw new IOException("Mikrofon nenahrává");
        DataOutputStream out=new DataOutputStream(socket.getOutputStream());
        out.writeInt(MAGIC);out.writeInt(RATE);out.flush();
        status="Vysílám mikrofon → Bluetooth";
        byte[] pcm=new byte[2048];
        while(go.get()){
            int count=record.read(pcm,0,pcm.length,AudioRecord.READ_BLOCKING);
            if(count<0)throw new IOException("AudioRecord chyba: "+count);
            if(count==0)continue;
            count &= ~1;
            if(count==0)continue;
            out.writeInt(count);
            out.write(pcm,0,count);
        }
    }
    private void closeServer(){
        BluetoothServerSocket current=server;server=null;
        if(current!=null)try{current.close();}catch(IOException ignored){}
    }
    private void clean(){
        AudioRecord r=microphone;microphone=null;
        if(r!=null){
            try{if(r.getRecordingState()==AudioRecord.RECORDSTATE_RECORDING)r.stop();}catch(IllegalStateException ignored){}
            r.release();
        }
        AudioTrack t=player;player=null;
        if(t!=null){
            try{if(t.getPlayState()==AudioTrack.PLAYSTATE_PLAYING)t.pause();t.flush();}catch(IllegalStateException ignored){}
            t.release();
        }
        closeServer();
        BluetoothSocket s=socket;socket=null;
        if(s!=null)try{s.close();}catch(IOException ignored){}
    }
    private void stopSession(){
        go.set(false);
        status="Zastavuji spojení…";
        clean();
    }
    @Override public void onDestroy(){stopSession();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
