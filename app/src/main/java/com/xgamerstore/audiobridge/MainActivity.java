package com.xgamerstore.audiobridge;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;

/** USB/microphone -> selected Android audio output. Bluetooth outputs must already be paired. */
public final class MainActivity extends Activity {
    private static final int BACK=0xFF07131C, CARD=0xFF102632, WHITE=0xFFF3FAFD, MINT=0xFF55E1C2, MUTED=0xFFB3C7D0;
    private AudioManager audio;
    private Spinner inputSpinner, outputSpinner;
    private TextView status, hint, gainText;
    private Button toggle;
    private int gain=100, requestedInput=-1, requestedOutput=-1;
    private final List<AudioDeviceInfo> inputs=new ArrayList<>(), outputs=new ArrayList<>();
    private final Handler handler=new Handler(Looper.getMainLooper());
    private boolean ticking;
    private final Runnable poll=new Runnable() {
        @Override public void run() {
            boolean on=RelayService.active;
            status.setText(RelayService.message);
            toggle.setText(on ? "■  Zastavit přenos" : "▶  Spustit přenos");
            if(ticking) handler.postDelayed(this,300);
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        audio=(AudioManager)getSystemService(AUDIO_SERVICE);
        getWindow().setStatusBarColor(BACK);
        getWindow().setNavigationBarColor(BACK);
        makeUi();
        refresh();
    }
    @Override public void onResume() { super.onResume(); ticking=true; refresh(); handler.post(poll); }
    @Override public void onPause() { ticking=false; handler.removeCallbacks(poll); super.onPause(); }
    private int dp(int n){return (int)(n*getResources().getDisplayMetrics().density+.5f);}
    private GradientDrawable bg(int color,int radius){
        GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;
    }
    private TextView text(String s,int size,int color,boolean bold){
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(color);
        if(bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return t;
    }
    private LinearLayout stack(){LinearLayout l=new LinearLayout(this);l.setOrientation(1);return l;}
    private void add(LinearLayout l, View v,int margin){
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(margin);l.addView(v,p);
    }
    private LinearLayout card(LinearLayout parent,String step,String title){
        LinearLayout c=stack();c.setPadding(dp(18),dp(18),dp(18),dp(18));c.setBackground(bg(CARD,18));
        add(c,text(step,12,MINT,true),0);add(c,text(title,19,WHITE,true),8);add(parent,c,14);return c;
    }
    private Button button(String label,boolean highlight){
        Button b=new Button(this);b.setAllCaps(false);b.setText(label);b.setTextSize(16);
        b.setTextColor(highlight?BACK:WHITE);b.setBackground(bg(highlight?MINT:0xFF2B4553,15));return b;
    }
    private void makeUi(){
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(BACK);
        LinearLayout root=stack();root.setPadding(dp(20),dp(30),dp(20),dp(36));scroll.addView(root);setContentView(scroll);
        add(root,text("◉ AUDIOBRIDGE",14,MINT,true),0);
        add(root,text("Zvuk bez hranic.",30,WHITE,true),14);
        add(root,text("USB / mikrofon → reproduktor nebo sluchátka",14,MUTED,false),6);
        LinearLayout summary=card(root,"PŘENOS ZVUKU","Stav spojení");
        status=text("Připraveno",16,WHITE,false);add(summary,status,10);
        LinearLayout ci=card(root,"01 · AUDIO VSTUP","Vyber zdroj");
        inputSpinner=new Spinner(this);add(ci,inputSpinner,10);
        LinearLayout co=card(root,"02 · AUDIO VÝSTUP","Vyber cíl");
        outputSpinner=new Spinner(this);add(co,outputSpinner,10);
        LinearLayout cg=card(root,"03 · HLASITOST","Zesílení signálu");
        gainText=text("100 %",16,MINT,true);add(cg,gainText,9);
        SeekBar seek=new SeekBar(this);seek.setMax(200);seek.setProgress(100);
        seek.setProgressTintList(android.content.res.ColorStateList.valueOf(MINT));
        seek.setThumbTintList(android.content.res.ColorStateList.valueOf(MINT));
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int v,boolean user){gain=v;gainText.setText(v+" %");RelayService.gain=v;}
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
        });add(cg,seek,8);
        toggle=button("▶  Spustit přenos",true);toggle.setOnClickListener(v->toggle());add(root,toggle,20);
        Button reload=button("↻  Obnovit zařízení",false);
        reload.setOnClickListener(v->refresh());add(root,reload,10);
        hint=text("Spáruj Bluetooth zařízení v nastavení telefonu. USB vstup musí podporovat záznam.",13,MUTED,false);add(root,hint,18);
        add(root,text("Pozor: vstupní mikrofon u reproduktoru může způsobit hlasitou zpětnou vazbu. Nepodporuje příjem A2DP z jiného telefonu.",12,MUTED,false),12);
    }
    private static boolean bt(AudioDeviceInfo d){
        int t=d.getType();
        return t==AudioDeviceInfo.TYPE_BLUETOOTH_SCO||t==AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || t==AudioDeviceInfo.TYPE_BLE_HEADSET||t==AudioDeviceInfo.TYPE_BLE_SPEAKER;
    }
    private boolean supported(AudioDeviceInfo d,boolean input){
        int t=d.getType();
        if(input)return t==AudioDeviceInfo.TYPE_BUILTIN_MIC || t==AudioDeviceInfo.TYPE_USB_DEVICE
                || t==AudioDeviceInfo.TYPE_USB_HEADSET || t==AudioDeviceInfo.TYPE_WIRED_HEADSET
                || t==AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t==AudioDeviceInfo.TYPE_BLE_HEADSET;
        return t==AudioDeviceInfo.TYPE_BUILTIN_SPEAKER || t==AudioDeviceInfo.TYPE_USB_DEVICE
                || t==AudioDeviceInfo.TYPE_USB_HEADSET || t==AudioDeviceInfo.TYPE_WIRED_HEADSET
                || t==AudioDeviceInfo.TYPE_WIRED_HEADPHONES || t==AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
                || t==AudioDeviceInfo.TYPE_BLUETOOTH_SCO || t==AudioDeviceInfo.TYPE_BLE_HEADSET
                || t==AudioDeviceInfo.TYPE_BLE_SPEAKER || t==AudioDeviceInfo.TYPE_HDMI;
    }
    private String label(AudioDeviceInfo d){
        String kind;
        switch(d.getType()){
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:kind="Mikrofon telefonu";break;
            case AudioDeviceInfo.TYPE_BUILTIN_SPEAKER:kind="Reproduktor telefonu";break;
            case AudioDeviceInfo.TYPE_USB_DEVICE:kind="USB audio";break;
            case AudioDeviceInfo.TYPE_USB_HEADSET:kind="USB headset";break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:kind="Bluetooth A2DP";break;
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:kind="Bluetooth handsfree";break;
            case AudioDeviceInfo.TYPE_BLE_HEADSET:kind="Bluetooth LE headset";break;
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:kind="Bluetooth LE reproduktor";break;
            default:kind="Zvukové zařízení";break;
        }
        String p=d.getProductName()==null?"":d.getProductName().toString();
        return p.isEmpty()?kind:kind+" • "+p;
    }
    private void populate(Spinner spin,List<AudioDeviceInfo> list){
        List<String> names=new ArrayList<>();
        for(AudioDeviceInfo d:list)names.add(label(d));
        if(names.isEmpty())names.add("Nenalezeno");
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_item,names){
            @Override public View getView(int pos,View convert,ViewGroup parent){return styled(super.getView(pos,convert,parent));}
            @Override public View getDropDownView(int pos,View convert,ViewGroup parent){return styled(super.getDropDownView(pos,convert,parent));}
            private View styled(View v){if(v instanceof TextView)((TextView)v).setTextColor(WHITE);return v;}
        };
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spin.setAdapter(adapter);spin.setEnabled(!list.isEmpty());
    }
    private void refresh(){
        if(inputSpinner==null)return;
        inputs.clear();outputs.clear();
        try{
            for(AudioDeviceInfo d:audio.getDevices(AudioManager.GET_DEVICES_INPUTS))if(supported(d,true))inputs.add(d);
            for(AudioDeviceInfo d:audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS))if(supported(d,false))outputs.add(d);
            populate(inputSpinner,inputs);populate(outputSpinner,outputs);
            if(inputs.isEmpty()||outputs.isEmpty())hint.setText("Připoj kompatibilní vstup a výstup.");
        }catch(SecurityException e){hint.setText("Povol přístup k Bluetooth v nastavení aplikace.");}
    }
    private void toggle(){
        if(RelayService.active){
            startService(new Intent(this,RelayService.class).setAction(RelayService.STOP));
            return;
        }
        if(inputs.isEmpty()||outputs.isEmpty()){hint.setText("Chybí zvukové zařízení.");return;}
        requestedInput=inputs.get(inputSpinner.getSelectedItemPosition()).getId();
        requestedOutput=outputs.get(outputSpinner.getSelectedItemPosition()).getId();
        if(checkSelfPermission(Manifest.permission.RECORD_AUDIO)!=PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO},1);return;
        }
        ensureBt();
    }
    private void ensureBt(){
        if(Build.VERSION.SDK_INT>=31&&checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)!=PackageManager.PERMISSION_GRANTED){
            boolean bluetooth=false;
            for(AudioDeviceInfo d:inputs)if(d.getId()==requestedInput)bluetooth=bt(d);
            for(AudioDeviceInfo d:outputs)if(d.getId()==requestedOutput)bluetooth|=bt(d);
            if(bluetooth){requestPermissions(new String[]{Manifest.permission.BLUETOOTH_CONNECT},2);return;}
        }
        Intent i=new Intent(this,RelayService.class).setAction(RelayService.START)
                .putExtra("input",requestedInput).putExtra("output",requestedOutput).putExtra("gain",gain);
        try{startForegroundService(i);status.setText("Spouštím přenos…");}
        catch(RuntimeException e){hint.setText("Přenos nelze spustit: "+e.getMessage());}
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED)
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},3);
    }
    @Override public void onRequestPermissionsResult(int code,String[] p,int[] grant){
        super.onRequestPermissionsResult(code,p,grant);
        if(grant.length>0 && grant[0]==PackageManager.PERMISSION_GRANTED){
            if(code==1||code==2)ensureBt();
        }else if(code==1||code==2)hint.setText("Bez potřebných oprávnění nelze spustit audio přenos.");
    }
}
