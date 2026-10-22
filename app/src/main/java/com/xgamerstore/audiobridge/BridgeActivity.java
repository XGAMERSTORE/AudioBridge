package com.xgamerstore.audiobridge;

import android.Manifest;
import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/** Two Android devices with this app communicate via an encrypted RFCOMM channel.
 * This does not implement the system A2DP Sink Bluetooth audio profile. */
public final class BridgeActivity extends Activity {
    private static final int DARK=0xFF08131C, CARD=0xFF102834, LIGHT=0xFFECFAFD, AQUA=0xFF64E6C8, DIM=0xFFAABEC9;
    private static final int REQ_BT=410, REQ_MIC=411;
    private TextView status, info;
    private Spinner outputs, peers;
    private Button listen, send;
    private BluetoothAdapter adapter;
    private final ArrayList<AudioDeviceInfo> outputList=new ArrayList<>();
    private final ArrayList<BluetoothDevice> peerList=new ArrayList<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean polling;
    private final Runnable statusLoop=new Runnable(){
        @Override public void run(){
            status.setText(BluetoothAudioService.status);
            boolean active=BluetoothAudioService.running;
            listen.setEnabled(!active);
            send.setEnabled(!active);
            if(polling)handler.postDelayed(this,400);
        }
    };
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(DARK);
        getWindow().setNavigationBarColor(DARK);
        adapter=BluetoothAdapter.getDefaultAdapter();
        makeUi();
        refresh();
    }
    @Override protected void onResume(){super.onResume();polling=true;handler.post(statusLoop);}
    @Override protected void onPause(){polling=false;handler.removeCallbacks(statusLoop);super.onPause();}
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+0.5f);}
    private GradientDrawable rounded(int color){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(14));return d;}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private TextView label(String content,int size,int color,boolean bold){
        TextView v=new TextView(this);v.setText(content);v.setTextColor(color);v.setTextSize(size);
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;
    }
    private void add(LinearLayout layout, View view,int margin){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(margin);layout.addView(view,p);
    }
    private LinearLayout panel(LinearLayout layout,String step,String title){
        LinearLayout p=column();p.setPadding(dp(18),dp(16),dp(18),dp(18));p.setBackground(rounded(CARD));
        add(p,label(step,12,AQUA,true),0);add(p,label(title,19,LIGHT,true),8);
        add(layout,p,16);return p;
    }
    private Button button(String title,boolean primary){
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextSize(16);
        b.setTextColor(primary?DARK:LIGHT);b.setBackground(rounded(primary?AQUA:0xFF244352));return b;
    }
    private void makeUi(){
        ScrollView s=new ScrollView(this);s.setFillViewport(true);s.setBackgroundColor(DARK);
        LinearLayout root=column();root.setPadding(dp(18),dp(26),dp(18),dp(36));s.addView(root);setContentView(s);
        add(root,label("AUDIOBRIDGE  ·  BLUETOOTH",13,AQUA,true),0);
        add(root,label("Bluetooth → jack",29,LIGHT,true),9);
        add(root,label("Spojení DVOU telefonů s AudioBridge. Nepřijímá běžný Bluetooth A2DP zvuk z televize, PC ani libovolného vysílače.",14,DIM,false),10);
        LinearLayout st=panel(root,"PŘIPOJENÍ","Stav");
        status=label("Připraveno",16,LIGHT,false);add(st,status,8);
        LinearLayout receiver=panel(root,"01 · TELEFON SE SLUCHÁTKY","Přijímat do jacku");
        add(receiver,label("Připoj kabelová 3,5mm sluchátka (nebo USB-C → jack). Vyber jejich výstup a spusť příjem.",14,DIM,false),8);
        outputs=new Spinner(this);add(receiver,outputs,12);
        listen=button("▶ Zapnout Bluetooth přijímač",true);
        listen.setOnClickListener(v->startReceive());add(receiver,listen,12);
        LinearLayout sender=panel(root,"02 · DRUHÝ TELEFON","Odesílat zvuk přes Bluetooth");
        add(sender,label("Na druhém telefonu nainstaluj stejnou aplikaci a spáruj telefony přes systémová nastavení. Tato verze vysílá zvuk z mikrofonu druhého telefonu.",14,DIM,false),9);
        peers=new Spinner(this);add(sender,peers,10);
        send=button("▶ Vysílat mikrofon",true);send.setOnClickListener(v->startSend());add(sender,send,12);
        Button stop=button("■ Zastavit spojení",false);
        stop.setOnClickListener(v->{startService(new Intent(this,BluetoothAudioService.class).setAction(BluetoothAudioService.STOP));status.setText("Zastavuji…");});
        add(root,stop,19);
        Button refresh=button("↻ Obnovit zařízení",false);refresh.setOnClickListener(v->refresh());add(root,refresh,11);
        Button settings=button("⚙ Nastavení párování Bluetooth",false);
        settings.setOnClickListener(v->startActivity(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS)));add(root,settings,11);
        info=label("Při prvním spuštění povol oprávnění k blízkým zařízením a na vysílajícím telefonu mikrofon.",12,DIM,false);add(root,info,17);
        add(root,label("Režim přijímače nepoužívá systémový Bluetooth profil A2DP Sink. Kompatibilní vysílač je druhý Android se spuštěným AudioBridge.",12,DIM,false),10);
    }
    private boolean hasBtPermission(){
        if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},REQ_BT);return false;
        }return true;
    }
    private void display(Spinner view,List<String> names){
        if(names.isEmpty())names.add("Nenalezeno");
        ArrayAdapter<String> a=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,names){
            private View recolor(View v){if(v instanceof TextView)((TextView)v).setTextColor(LIGHT);return v;}
            @Override public View getView(int pos,View old,ViewGroup parent){return recolor(super.getView(pos,old,parent));}
            @Override public View getDropDownView(int pos,View old,ViewGroup parent){return recolor(super.getDropDownView(pos,old,parent));}
        };
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        view.setAdapter(a);
    }
    private static boolean wired(AudioDeviceInfo d){
        int type=d.getType();
        return type==AudioDeviceInfo.TYPE_WIRED_HEADPHONES||type==AudioDeviceInfo.TYPE_WIRED_HEADSET||
                type==AudioDeviceInfo.TYPE_USB_HEADSET||type==AudioDeviceInfo.TYPE_USB_DEVICE;
    }
    private void refresh(){
        outputList.clear();peerList.clear();
        AudioManager manager=(AudioManager)getSystemService(AUDIO_SERVICE);
        AudioDeviceInfo speaker=null;
        for(AudioDeviceInfo d:manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)){
            if(wired(d))outputList.add(d);
            if(d.getType()==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER)speaker=d;
        }
        if(speaker!=null)outputList.add(speaker);
        List<String> outs=new ArrayList<>();
        for(AudioDeviceInfo d:outputList){
            String n=d.getProductName()==null?"":d.getProductName().toString();
            outs.add((wired(d)?"🎧 Sluchátka / USB":"🔊 Reproduktor telefonu")+(n.isEmpty()?"":" · "+n));
        }
        display(outputs,outs);
        if(adapter==null){info.setText("Telefon nepodporuje Bluetooth Classic.");display(peers,new ArrayList<>());return;}
        if(!hasBtPermission())return;
        if(!adapter.isEnabled()){info.setText("Zapni Bluetooth a pak obnov seznam.");display(peers,new ArrayList<>());return;}
        try{
            Set<BluetoothDevice> bonded=adapter.getBondedDevices();
            if(bonded!=null)peerList.addAll(bonded);
            peerList.sort(Comparator.comparing(d->d.getName()==null?"":d.getName().toLowerCase()));
            List<String> names=new ArrayList<>();
            for(BluetoothDevice d:peerList){
                String name=d.getName();
                names.add((name==null||name.trim().isEmpty()?"Neznámé zařízení":name)+" · "+d.getAddress());
            }
            display(peers,names);
            info.setText("V přijímači stiskni 'Zapnout', na druhém telefonu vyber přijímač a spusť vysílání.");
        }catch(SecurityException e){info.setText("Chybí oprávnění BLUETOOTH_CONNECT.");}
    }
    private void startReceive(){
        if(!hasBtPermission())return;
        if(adapter==null||!adapter.isEnabled()){info.setText("Nejprve zapni Bluetooth.");return;}
        if(outputList.isEmpty()){info.setText("Nenalezen audio výstup.");return;}
        int index=outputs.getSelectedItemPosition();
        if(index<0||index>=outputList.size())return;
        int selected=outputList.get(index).getId();
        Intent i=new Intent(this,BluetoothAudioService.class).setAction(BluetoothAudioService.RECEIVE).putExtra("output",selected);
        try{startForegroundService(i);status.setText("Spouštím příjem…");}catch(RuntimeException e){info.setText("Nelze spustit příjem: "+e.getMessage());}
    }
    private void startSend(){
        if(!hasBtPermission())return;
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},REQ_MIC);return;
        }
        if(adapter==null||!adapter.isEnabled()){info.setText("Nejprve zapni Bluetooth.");return;}
        int index=peers.getSelectedItemPosition();
        if(index<0||index>=peerList.size()){info.setText("Nejprve spáruj druhý telefon v nastavení Bluetooth.");return;}
        Intent i=new Intent(this,BluetoothAudioService.class).setAction(BluetoothAudioService.SEND).putExtra("peer",peerList.get(index).getAddress());
        try{startForegroundService(i);status.setText("Připojuji…");}catch(RuntimeException e){info.setText("Nelze spustit vysílání: "+e.getMessage());}
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] grants){
        super.onRequestPermissionsResult(request,permissions,grants);
        if(grants.length>0&&grants[0]==PackageManager.PERMISSION_GRANTED){
            if(request==REQ_BT)refresh();
            else info.setText("Mikrofon povolen, nyní spusť vysílání.");
        }else info.setText("Bez tohoto oprávnění funkce nefunguje.");
    }
}
