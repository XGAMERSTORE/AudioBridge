package com.xgamerstore.audiobridge;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
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
import java.util.concurrent.atomic.AtomicBoolean;

/** Routes microphone/USB audio captured by Android to a selected output. No A2DP sink. */
public final class RelayService extends Service {
    public static final String START="com.xgamerstore.audiobridge.START",STOP="com.xgamerstore.audiobridge.STOP";
    public static volatile boolean active=false;
    public static volatile String message="Připraveno";
    public static volatile int gain=100;
    private final AtomicBoolean running=new AtomicBoolean(false);
    private AudioManager audio;
    private volatile AudioRecord record;
    private volatile AudioTrack track;
    private boolean communicationChanged;
    private int previousMode;

    @Override public void onCreate(){
        super.onCreate();audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        ((NotificationManager)getSystemService(NOTIFICATION_SERVICE)).createNotificationChannel(
                new NotificationChannel("relay","Živý audio přenos",NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent i,int flags,int id){
        if(i==null)return START_NOT_STICKY;
        if(STOP.equals(i.getAction())){stopRelay();stopSelf();return START_NOT_STICKY;}
        if(!START.equals(i.getAction())||running.get())return START_NOT_STICKY;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            message="Chybí oprávnění k mikrofonu";stopSelf();return START_NOT_STICKY;
        }
        Intent click=new Intent(this,MainActivity.class);
        PendingIntent open=PendingIntent.getActivity(this,0,click,PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop=PendingIntent.getService(this,1,new Intent(this,RelayService.class).setAction(STOP),
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n=new Notification.Builder(this,"relay")
                .setSmallIcon(android.R.drawable.ic_media_play).setContentTitle("AudioBridge: aktivní přenos")
                .setContentText("USB / mikrofon → reproduktor / sluchátka").setContentIntent(open)
                .setOngoing(true).addAction(new Notification.Action.Builder(
                        android.R.drawable.ic_media_pause,"Zastavit",stop).build()).build();
        try{
            if(Build.VERSION.SDK_INT>=29)
                startForeground(501,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
            else startForeground(501,n);
        }catch(RuntimeException e){message="Službu nelze spustit: "+e.getMessage();stopSelf();return START_NOT_STICKY;}
        final int in=i.getIntExtra("input",-1),out=i.getIntExtra("output",-1);
        gain=i.getIntExtra("gain",100);
        message="Připojuji zvuk…";running.set(true);
        new Thread(()->relay(in,out),"AudioBridge-PCM").start();
        return START_NOT_STICKY;
    }
    private AudioDeviceInfo device(int id,int direction){
        for(AudioDeviceInfo d:audio.getDevices(direction))if(d.getId()==id)return d;
        return null;
    }
    private void bluetoothInput(AudioDeviceInfo input){
        int type=input.getType();
        if(type!=AudioDeviceInfo.TYPE_BLUETOOTH_SCO && type!=AudioDeviceInfo.TYPE_BLE_HEADSET)return;
        previousMode=audio.getMode();communicationChanged=true;
        audio.setMode(AudioManager.MODE_IN_COMMUNICATION);
        if(Build.VERSION.SDK_INT>=31)audio.setCommunicationDevice(input);
        else audio.startBluetoothSco();
    }
    private void restore(){
        if(!communicationChanged)return;
        try{
            if(Build.VERSION.SDK_INT>=31)audio.clearCommunicationDevice();
            else audio.stopBluetoothSco();
            audio.setMode(previousMode);
        }catch(RuntimeException ignored){}
        communicationChanged=false;
    }
    private void relay(int in,int out){
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO);
        AudioRecord r=null;AudioTrack t=null;
        try{
            AudioDeviceInfo source=device(in,AudioManager.GET_DEVICES_INPUTS);
            AudioDeviceInfo target=device(out,AudioManager.GET_DEVICES_OUTPUTS);
            if(source==null||target==null){message="Vstup nebo výstup byl odpojen";return;}
            bluetoothInput(source);
            int block=0;
            for(int rate:new int[]{48000,44100,16000,8000}){
                try{
                    int imin=AudioRecord.getMinBufferSize(rate,AudioFormat.CHANNEL_IN_MONO,AudioFormat.ENCODING_PCM_16BIT);
                    int omin=AudioTrack.getMinBufferSize(rate,AudioFormat.CHANNEL_OUT_MONO,AudioFormat.ENCODING_PCM_16BIT);
                    if(imin<=0||omin<=0)continue;
                    int b=Math.max(320,rate/50*2);
                    AudioFormat inputFormat=new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_IN_MONO).build();
                    AudioFormat outputFormat=new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(rate).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build();
                    r=new AudioRecord.Builder().setAudioSource(MediaRecorder.AudioSource.MIC)
                            .setAudioFormat(inputFormat).setBufferSizeInBytes(Math.max(imin*2,b*4)).build();
                    if(r.getState()!=AudioRecord.STATE_INITIALIZED||!r.setPreferredDevice(source)){
                        r.release();r=null;continue;
                    }
                    t=new AudioTrack.Builder().setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
                            .setAudioFormat(outputFormat).setBufferSizeInBytes(Math.max(omin*2,b*4))
                            .setTransferMode(AudioTrack.MODE_STREAM).build();
                    if(t.getState()!=AudioTrack.STATE_INITIALIZED||!t.setPreferredDevice(target)){
                        t.release();t=null;r.release();r=null;continue;
                    }
                    block=b;break;
                }catch(RuntimeException e){
                    if(r!=null){r.release();r=null;}if(t!=null){t.release();t=null;}
                }
            }
            if(r==null||t==null){message="Android nepodporuje toto směrování zvuku";return;}
            record=r;track=t;
            r.startRecording();t.play();
            if(r.getRecordingState()!=AudioRecord.RECORDSTATE_RECORDING){message="Mikrofon nezaznamenává";return;}
            active=true;message="Přenos běží";
            byte[] pcm=new byte[block];int frames=0;
            while(running.get()){
                int n=r.read(pcm,0,pcm.length,AudioRecord.READ_BLOCKING);
                if(n<0){message="Chyba čtení audio vstupu: "+n;break;}
                if(n==0)continue;
                int vol=Math.max(0,Math.min(200,gain));
                if(vol!=100){
                    for(int j=0;j+1<n;j+=2){
                        int sample=(short)((pcm[j]&255)|(pcm[j+1]<<8));
                        int x=Math.max(-32768,Math.min(32767,sample*vol/100));
                        pcm[j]=(byte)x;pcm[j+1]=(byte)(x>>8);
                    }
                }
                int sent=0;
                while(running.get()&&sent<n){
                    int m=t.write(pcm,sent,n-sent,AudioTrack.WRITE_BLOCKING);
                    if(m<=0)throw new IllegalStateException("AudioTrack write: "+m);
                    sent+=m;
                }
                if(++frames%50==0){
                    if(device(in,AudioManager.GET_DEVICES_INPUTS)==null||
                            device(out,AudioManager.GET_DEVICES_OUTPUTS)==null){
                        message="Zvukové zařízení bylo odpojeno";break;
                    }
                    AudioDeviceInfo routed=t.getRoutedDevice();
                    message=routed!=null&&routed.getId()!=out?"Android přesměroval zvuk jinam":"Přenos běží";
                }
            }
        }catch(Exception e){if(running.get())message="Přenos ukončen: "+e.getMessage();}
        finally{
            running.set(false);active=false;
            if(r!=null){try{r.stop();}catch(RuntimeException ignored){}r.release();}
            if(t!=null){try{t.pause();t.flush();}catch(RuntimeException ignored){}t.release();}
            record=null;track=null;restore();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();
        }
    }
    private void stopRelay(){
        running.set(false);active=false;message="Přenos zastaven";
        AudioRecord current=record;
        if(current!=null)try{current.stop();}catch(RuntimeException ignored){}
    }
    @Override public void onDestroy(){if(running.get())stopRelay();super.onDestroy();}
    @Override public IBinder onBind(Intent intent){return null;}
}
