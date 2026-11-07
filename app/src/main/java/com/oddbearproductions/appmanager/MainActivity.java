package com.oddbearproductions.appmanager;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ShortcutInfo;
import android.content.pm.ShortcutManager;
import android.graphics.drawable.Icon;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class MainActivity extends Activity {
    private static final int PICK_APKS=10, PICK_FOLDER=11, PICK_LOG_FOLDER=12,
            PERMIT_INSTALL=13, UNINSTALL=14, PICK_ICON_FOLDER=15;
    private static final int BG=Color.rgb(23,28,34), PANEL=Color.rgb(35,41,49),
            TEXT=Color.rgb(247,238,226), MUTED=Color.rgb(173,182,193),
            ORANGE=Color.rgb(235,127,49);
    private static WeakReference<MainActivity> active = new WeakReference<>(null);
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ApkRepository repository = new ApkRepository();
    private LinearLayout content, root;
    private String tab = "APKs", lastOutcomeDialog = "", lastRecoveryPrompt = "";
    private boolean resumed;
    private ApkRepository.Apk pendingInstall, pendingReinstall;
    private final ArrayList<Uri> pendingImports = new ArrayList<>();
    private final Runnable periodic = new Runnable() {
        @Override public void run() {
            if (resumed) { if ("APKs".equals(tab)) refresh(); handler.postDelayed(this,30000L); }
        }
    };

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.rgb(18,22,27));
        getWindow().setNavigationBarColor(Color.rgb(18,22,27));
        renderShell();
        handleExternalIntent(getIntent());
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent); handleExternalIntent(intent);
    }
    @Override protected void onResume() {
        super.onResume(); resumed=true; active=new WeakReference<>(this);
        handler.removeCallbacks(periodic); handler.postDelayed(periodic,30000);
        refresh();
        checkInterruptedReinstall();
    }
    @Override protected void onPause() {
        resumed=false; active.clear(); handler.removeCallbacks(periodic);
        super.onPause();
    }
    @Override protected void onDestroy() {
        handler.removeCallbacks(periodic); io.shutdown(); super.onDestroy();
    }
    static boolean tryLaunchApproval(Intent approval) {
        MainActivity a=active.get();
        if (a == null || !a.resumed) return false;
        a.runOnUiThread(() -> a.launchApproval(approval));
        return true;
    }
    static void statusChanged(String id, int status) {
        MainActivity a=active.get();
        if (a == null || !a.resumed) return;
        a.runOnUiThread(() -> {
            a.refresh();
            JSONObject latest=HistoryStore.get(a,id);
            if (latest != null && latest.optString("status").startsWith("Conflict"))
                a.offerReinstallFromHistory(id);
        });
    }
    private void handleExternalIntent(Intent intent) {
        if (intent == null) return;
        String act=intent.getAction();
        if ("com.obp.appmanager.SHOW_APPS".equals(act)) {
            tab="Apps"; renderShell(); refresh(); intent.setAction("handled"); return;
        }
        if ("com.obp.appmanager.OPEN_LOG".equals(act)) {
            tab="Logs"; renderShell(); refresh(); intent.setAction("handled"); return;
        }
        if (Intent.ACTION_SEND.equals(act) || Intent.ACTION_VIEW.equals(act)) {
            Uri incoming=null;
            if (Intent.ACTION_SEND.equals(act)) {
                incoming = Build.VERSION.SDK_INT >= 33
                        ? intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri.class)
                        : (Uri)intent.getParcelableExtra(Intent.EXTRA_STREAM);
            } else incoming=intent.getData();
            if (incoming != null) {
                final Uri candidate = incoming;
                // SEND and VIEW are public entry points. Require an explicit tap
                // before copying external content into our managed installer folder.
                new AlertDialog.Builder(this)
                        .setTitle("Import external APK?")
                        .setMessage("Import this file into your selected installer folder? This does not install it.")
                        .setNegativeButton("Cancel",null)
                        .setPositiveButton("Import",(dialog,which)->{
                            pendingImports.add(candidate);
                            tab="APKs"; renderShell(); importOrChooseFolder();
                        }).show();
            }
            intent.setAction("handled");
        }
    }
    private void launchApproval(Intent systemIntent) {
        try { startActivity(systemIntent); }
        catch (Exception ex) { toast("Android approval could not start: " + ex.getMessage()); }
    }
    private int dp(float v) { return (int)(getResources().getDisplayMetrics().density*v+0.5f); }
    private GradientDrawable background(int color, int radius, boolean border) {
        GradientDrawable d=new GradientDrawable(); d.setColor(color);d.setCornerRadius(dp(radius));
        if (border) d.setStroke(dp(1),ORANGE); return d;
    }
    private LinearLayout column() {
        LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;
    }
    private TextView label(String text, int sp, boolean bold, int color) {
        TextView t=new TextView(this);t.setText(text);t.setTextSize(sp);t.setTextColor(color);
        if (bold)t.setTypeface(Typeface.DEFAULT,Typeface.BOLD);
        return t;
    }
    private Button button(String title, boolean primary, View.OnClickListener listener) {
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setTextSize(13);
        b.setTextColor(primary?Color.rgb(20,22,26):TEXT);
        b.setBackground(background(primary?ORANGE:PANEL,9,!primary));
        b.setOnClickListener(listener);b.setMinHeight(dp(44));b.setMinimumHeight(dp(44));return b;
    }
    private void renderShell() {
        root=column();root.setBackgroundColor(BG);root.setPadding(dp(15),dp(16),dp(15),dp(12));
        if (Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((v,insets)->{
                android.graphics.Insets bar=insets.getInsets(
                        android.view.WindowInsets.Type.systemBars());
                v.setPadding(dp(15), dp(16)+bar.top, dp(15), dp(12)+bar.bottom);
                return insets;
            });
        }
        setContentView(root);
        TextView header=label("OBP  /  APP MANAGER",23,true,TEXT);root.addView(header);
        TextView caption=label("Private Android testing utility · M1",12,false,MUTED);
        root.addView(caption);
        LinearLayout tabs=new LinearLayout(this);tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(0,dp(17),0,dp(10));
        for (String choice : new String[]{"APKs","Apps","Logs"}) {
            Button b=button(choice,choice.equals(tab),v->{tab=choice;renderShell();refresh();});
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(47),1);
            lp.setMargins(dp(2),0,dp(2),0);tabs.addView(b,lp);
        }
        root.addView(tabs);
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);
        content=column();content.setPadding(dp(2),dp(3),dp(2),dp(10));
        scroll.addView(content);
        root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView footer=label("Android confirmations required · APKs removed only after verified installs",11,false,MUTED);
        root.addView(footer);
    }
    private void section(String title, String description) {
        TextView h=label(title,19,true,TEXT);h.setPadding(0,dp(12),0,dp(6));content.addView(h);
        if (description != null && !description.isEmpty()) {
            TextView p=label(description,12,false,MUTED);p.setPadding(0,0,0,dp(9));content.addView(p);
        }
    }
    private LinearLayout card() {
        LinearLayout l=column();l.setPadding(dp(13),dp(12),dp(13),dp(12));
        l.setBackground(background(PANEL,12,false));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,dp(10));
        content.addView(l,lp);return l;
    }
    private void line(LinearLayout l,String value,int sp,boolean strong,int color) {
        TextView t=label(value,sp,strong,color);t.setPadding(0,0,0,dp(6));l.addView(t);
    }
    private void actions(LinearLayout card, Button... buttons) {
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);
        for (Button b:buttons) {
            LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,dp(45),1);
            lp.setMargins(dp(2),dp(5),dp(2),0);row.addView(b,lp);
        }
        card.addView(row);
    }
    private void refresh() {
        if (content==null)return;
        content.removeAllViews();
        if ("APKs".equals(tab)) showApks();
        else if ("Apps".equals(tab)) showInstalled();
        else showLogs();
    }
    private void showApks() {
        section("Incoming installers","Select a dedicated folder, e.g. Downloads/OBP Installers. You can also import APKs directly from Downloads.");
        LinearLayout choose=card();
        Uri folder=ApkRepository.tree(this,"apks_tree");
        line(choose,folder==null?"No APK folder selected":"APK folder connected",14,true,folder==null?MUTED:TEXT);
        actions(choose,button("Choose folder",true,v->pickTree(PICK_FOLDER)),
                button("Add APKs",false,v->pickApks()));
        actions(choose,button("Refresh",false,v->refresh()),
                button("Open logs",false,v->{tab="Logs";renderShell();refresh();}));
        if (folder==null) return;
        section("Available APKs","The app checks this folder on opening and every 30 seconds while visible.");
        line(content,"Reading APKs…",13,false,MUTED);
        io.execute(()->{
            try {
                List<ApkRepository.Apk> list=repository.scan(getApplicationContext(),folder);
                runOnUiThread(()->{
                    if(!"APKs".equals(tab))return;
                    // A new scan may be scheduled while UI stays on the same tab.
                    refreshApkCards(list);
                });
            } catch(Exception ex) {
                runOnUiThread(()->{if("APKs".equals(tab)){
                    content.removeViewAt(content.getChildCount()-1);
                    line(content,"Folder access failed: " + ex.getMessage(),13,false,MUTED);
                }});
            }
        });
    }
    private void refreshApkCards(List<ApkRepository.Apk> list) {
        // Re-render the static selection controls without re-entering asynchronous scan.
        content.removeAllViews();
        section("Incoming installers","APK source folder is connected.");
        LinearLayout tools=card();
        actions(tools,button("Add APKs",true,v->pickApks()),
                button("Change folder",false,v->pickTree(PICK_FOLDER)));
        section("Available APKs",list.size()+" installer(s) found");
        if(list.isEmpty()) line(content,"No APKs yet. Add files from Downloads or copy them into the selected folder.",13,false,MUTED);
        for(ApkRepository.Apk a:list) {
            LinearLayout c=card();line(c,a.filename,14,true,TEXT);
            if (!a.valid()) {line(c,"Invalid APK: " + a.error,12,false,MUTED);continue;}
            long installed=ApkRepository.installedCode(this,a.pkg);
            String ver="v"+a.version+" ("+a.versionCode+")";
            line(c,a.pkg,12,false,MUTED);
            line(c,ver + (installed>=0?" · Installed build "+installed:" · Not installed"),12,false,MUTED);
            actions(c,button(installed>=0?"Update":"Install",true,v->requestInstall(a,"Update")),
                    button("Details",false,v->new AlertDialog.Builder(this)
                            .setTitle(a.filename).setMessage("Package: "+a.pkg+"\nVersion: "+a.version+
                                    "\nBuild code: "+a.versionCode+"\nFile size: "+a.size+" bytes")
                            .setPositiveButton("OK",null).show()));
        }
    }
    private void showInstalled() {
        section("OBP apps", "One place for your installed OBP applications and their actual icons.");
        LinearLayout controls=card();
        line(controls, ApkRepository.tree(this,"icons_tree") == null
                ? "PNG icon folder not selected" : "PNG icon folder connected", 13, false, MUTED);
        actions(controls,button("Pin Apps shortcut",true,v->pinAppsShortcut()),
                button("Set icon folder",false,v->pickTree(PICK_ICON_FOLDER)));
        line(controls,"Icons are also saved automatically after a verified APK installation when a folder is selected.",11,false,MUTED);
        line(content,"Checking installed applications…",13,false,MUTED);
        io.execute(()->{
            ArrayList<String[]> apps=new ArrayList<>();
            PackageManager pm=getPackageManager();
            try {
                java.util.HashSet<String> managed = new java.util.HashSet<>();
                JSONArray history = HistoryStore.all(getApplicationContext());
                for(int i=0;i<history.length();i++) {
                    JSONObject record=history.optJSONObject(i);
                    if(record != null) managed.add(record.optString("package"));
                }
                for(PackageInfo p:pm.getInstalledPackages(0)) {
                    if (p.applicationInfo == null) continue;
                    String title=String.valueOf(pm.getApplicationLabel(p.applicationInfo));
                    if (!InstallPolicy.looksLikeObp(p.packageName,title) && !managed.contains(p.packageName))continue;
                    String version=p.versionName==null?"unknown":p.versionName;
                    apps.add(new String[]{title,p.packageName,version});
                }
                apps.sort((x,y)->x[0].compareToIgnoreCase(y[0]));
            } catch(Exception ignored) {}
            runOnUiThread(()->{
                if(!"Apps".equals(tab))return;
                content.removeAllViews();
                section("OBP apps", apps.size()+" installed application(s)");
                LinearLayout controlsAgain=card();
                line(controlsAgain, ApkRepository.tree(this,"icons_tree")==null
                        ? "Choose a folder to save icons as PNG files" : "Icon PNG folder connected", 12,false,MUTED);
                actions(controlsAgain,button("Pin Apps shortcut",true,v->pinAppsShortcut()),
                        button("Icon folder",false,v->pickTree(PICK_ICON_FOLDER)));
                if(ApkRepository.tree(this,"icons_tree") != null) {
                    actions(controlsAgain,button("Export all icons",false,v->exportIcons(apps)));
                }
                if(apps.isEmpty()) {
                    line(content,"No recognised OBP apps were found.",13,false,MUTED);
                    return;
                }
                // Render launchable icons in a three-column grid instead of a long list.
                LinearLayout row=null;
                for(int index=0; index<apps.size(); index++) {
                    if(index%3==0) {
                        row=new LinearLayout(this);
                        row.setOrientation(LinearLayout.HORIZONTAL);
                        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);
                        rp.setMargins(0,0,0,dp(8));content.addView(row,rp);
                    }
                    final String[] item=apps.get(index);
                    LinearLayout tile=column();
                    tile.setGravity(android.view.Gravity.CENTER_HORIZONTAL);
                    tile.setPadding(dp(4),dp(12),dp(4),dp(10));
                    tile.setBackground(background(PANEL,12,false));
                    LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(0,dp(130),1);
                    tp.setMargins(dp(2),0,dp(2),0);row.addView(tile,tp);
                    ImageView icon=new ImageView(this);
                    try {icon.setImageDrawable(pm.getApplicationIcon(item[1]));}
                    catch(Exception ignored){icon.setImageResource(android.R.drawable.sym_def_app_icon);}
                    LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(52),dp(52));
                    ip.bottomMargin=dp(8);tile.addView(icon,ip);
                    TextView name=label(item[0],12,true,TEXT);
                    name.setGravity(android.view.Gravity.CENTER);
                    name.setMaxLines(2);tile.addView(name);
                    TextView version=label("v"+item[2],10,false,MUTED);
                    version.setGravity(android.view.Gravity.CENTER);tile.addView(version);
                    tile.setOnClickListener(v->{
                        Intent open=pm.getLaunchIntentForPackage(item[1]);
                        if(open==null) toast("App has no launch activity");
                        else try {startActivity(open);}catch(Exception ex){toast(ex.getMessage());}
                    });
                    tile.setOnLongClickListener(v->{
                        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:"+item[1])));return true;
                    });
                }
            });
        });
    }

    private void pinAppsShortcut() {
        if (Build.VERSION.SDK_INT < 26) {
            toast("Home screen pinning needs Android 8 or newer");return;
        }
        ShortcutManager shortcuts=getSystemService(ShortcutManager.class);
        if(shortcuts==null || !shortcuts.isRequestPinShortcutSupported()) {
            toast("Your home screen launcher does not support shortcut pinning");return;
        }
        Intent open=new Intent(this,MainActivity.class);
        open.setAction("com.obp.appmanager.SHOW_APPS");
        open.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        ShortcutInfo info=new ShortcutInfo.Builder(this,"obp-apps-shortcut")
                .setShortLabel("OBP Apps").setLongLabel("Open OBP Apps")
                .setIcon(Icon.createWithResource(this,android.R.drawable.ic_menu_manage))
                .setIntent(open).build();
        try {
            if(shortcuts.requestPinShortcut(info,null))toast("Approve the OBP Apps shortcut on your home screen");
            else toast("Shortcut request was rejected by your launcher");
        }catch(Exception ex){toast("Shortcut could not be requested: "+ex.getMessage());}
    }

    private void exportIcons(List<String[]> apps) {
        if(ApkRepository.tree(this,"icons_tree") == null) {
            pickTree(PICK_ICON_FOLDER);return;
        }
        ArrayList<String> names=new ArrayList<>();
        for(String[] app:apps)names.add(app[1]);
        io.execute(()->{
            int count=IconExporter.exportAll(getApplicationContext(),names);
            runOnUiThread(()->toast("Exported "+count+" of "+names.size()+" PNG icon(s)"));
        });
    }

    private void showLogs() {
        Uri folder=ApkRepository.tree(this,"logs_tree");
        JSONArray logs=HistoryStore.all(this);int pending=0;
        for(int i=0;i<logs.length();i++){
            JSONObject x=logs.optJSONObject(i);
            if(x!=null && x.optBoolean("pendingLog"))pending++;
        }
        section("Diagnostics","Installation attempts are saved locally. Failures can be automatically written to a selected folder, including a supported Drive provider.");
        LinearLayout settings=card();
        line(settings,folder==null?"Automatic export: not configured":"Automatic export: folder selected",14,true,TEXT);
        line(settings,pending+" failed log(s) awaiting export",12,false,MUTED);
        actions(settings,button("Choose log folder",true,v->pickTree(PICK_LOG_FOLDER)),
                button("Retry exports",false,v->{FailureLogSync.flushAsync(this);toast("Export retry queued");}));
        StringBuilder all=new StringBuilder();
        for(int i=0;i<logs.length();i++) {
            JSONObject item=logs.optJSONObject(i);
            if(item!=null)all.append(HistoryStore.text(item)).append("\n-----\n");
        }
        String allText=all.toString();
        actions(settings,button("Copy all",false,v->copy(allText)),
                button("Share all",false,v->share(allText)));
        section("Install history",logs.length()+" recorded attempt(s)");
        if(logs.length()==0)line(content,"No installations attempted yet.",13,false,MUTED);
        for(int i=0;i<Math.min(logs.length(),100);i++) {
            JSONObject item=logs.optJSONObject(i);if(item==null)continue;
            String status=item.optString("status");
            LinearLayout c=card();line(c,item.optString("app")+" · "+item.optString("version"),14,true,TEXT);
            line(c,status+" · "+item.optString("operation"),12,false,status.startsWith("Failed")||status.startsWith("Conflict")?ORANGE:MUTED);
            String error=item.optString("error");
            if(!error.isEmpty())line(c,error,12,false,MUTED);
            String contents=HistoryStore.text(item);
            actions(c,button("Copy",true,v->copy(contents)),button("Share",false,v->share(contents)));
            if(status.startsWith("Conflict"))actions(c,button("Reinstall (data loss)",false,
                    v->offerReinstallFromHistory(item.optString("id"))));
        }
    }
    private void copy(String s) {
        ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(
                ClipData.newPlainText("OBP install logs",s));toast("Copied log to clipboard");
    }
    private void share(String s) {
        Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT,"OBP App Manager diagnostic");i.putExtra(Intent.EXTRA_TEXT,s);
        startActivity(Intent.createChooser(i,"Export diagnostic"));
    }
    private void pickTree(int code) {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i,code);
    }
    private void pickApks() {
        if (ApkRepository.tree(this,"apks_tree")==null) {toast("Choose installer folder first");pickTree(PICK_FOLDER);return;}
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i,PICK_APKS);
    }
    @Override protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if ((request==PICK_FOLDER||request==PICK_LOG_FOLDER||request==PICK_ICON_FOLDER)&&result==RESULT_OK&&data!=null) {
            Uri tree=data.getData();if(tree==null)return;
            final int requiredFlags=Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION;
            final int grantedFlags=data.getFlags();
            try {
                if ((grantedFlags & requiredFlags)!=requiredFlags)
                    throw new SecurityException("Selected provider requires read and write access");
                getContentResolver().takePersistableUriPermission(tree,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
                ApkRepository.setTree(this,request==PICK_FOLDER?"apks_tree":request==PICK_ICON_FOLDER?"icons_tree":"logs_tree",tree);
                if(request==PICK_LOG_FOLDER)FailureLogSync.flushAsync(this);
                if(request==PICK_FOLDER && !pendingImports.isEmpty()) importOrChooseFolder();
                refresh();
            } catch(Exception ex){toast("Folder permission failed: "+ex.getMessage());}
        } else if (request==PICK_APKS && result==RESULT_OK && data!=null) {
            if(data.getClipData()!=null) {
                for(int i=0;i<data.getClipData().getItemCount();i++)
                    pendingImports.add(data.getClipData().getItemAt(i).getUri());
            } else if(data.getData()!=null)pendingImports.add(data.getData());
            importOrChooseFolder();
        } else if(request==PERMIT_INSTALL) {
            if (Build.VERSION.SDK_INT>=26 && getPackageManager().canRequestPackageInstalls() && pendingInstall!=null) {
                ApkRepository.Apk apk=pendingInstall;pendingInstall=null;startInstall(apk,"Update");
            } else {toast("Android installation permission is required");pendingInstall=null;}
        } else if(request==UNINSTALL) {
            ApkRepository.Apk apk=pendingReinstall;pendingReinstall=null;
            if (apk!=null) {
                getSharedPreferences("settings",MODE_PRIVATE).edit().remove("pending_reinstall_id").apply();
                lastOutcomeDialog="";
                if (!ApkRepository.installed(this,apk.pkg)) {
                    toast("Previous app removed. Installing replacement.");
                    requestInstall(apk,"Reinstall after uninstall");
                } else toast("Uninstall cancelled. Existing app retained; APK retained.");
            } else checkInterruptedReinstall();
        }
    }
    private String getFilename(Uri uri) {
        String name=null;
        try(android.database.Cursor c=getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME},null,null,null)){
            if(c!=null && c.moveToFirst())name=c.getString(0);
        } catch(Exception ignored){}
        if(name==null)name=uri.getLastPathSegment();
        return name==null?"unknown.apk":name;
    }
    private void importOrChooseFolder() {
        Uri folder=ApkRepository.tree(this,"apks_tree");
        if(folder==null){toast("Choose an installer folder for imported APKs");pickTree(PICK_FOLDER);return;}
        if(pendingImports.isEmpty())return;
        List<Uri> imports=new ArrayList<>(pendingImports);pendingImports.clear();
        toast("Importing "+imports.size()+" APK(s)…");
        io.execute(()->{
            int count=0;String error="";
            for(Uri uri:imports){
                String name=getFilename(uri);
                if(!InstallPolicy.isApkName(name)){error="Skipped non-APK file: "+name;continue;}
                try{ApkRepository.importApk(getApplicationContext(),folder,uri,name);count++;}
                catch(Exception ex){error="Import failed: "+ex.getMessage();}
            }
            final int done=count;final String issue=error;
            runOnUiThread(()->{toast(done+" APK(s) imported. "+issue);refresh();});
        });
    }
    private void requestInstall(ApkRepository.Apk a,String operation) {
        if(Build.VERSION.SDK_INT>=26 && !getPackageManager().canRequestPackageInstalls()) {
            pendingInstall=a;
            new AlertDialog.Builder(this).setTitle("Allow APK installation")
                    .setMessage("Android needs you to allow OBP App Manager to install unknown apps. You can revoke this later.")
                    .setNegativeButton("Cancel",null)
                    .setPositiveButton("Open Settings",(d,w)->{
                        Intent i=new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:"+getPackageName()));
                        startActivityForResult(i,PERMIT_INSTALL);
                    }).show();return;
        }
        if(Build.VERSION.SDK_INT>=33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                !=PackageManager.PERMISSION_GRANTED) {
            // Notifications are optional; a denied permission never blocks foreground installs.
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS},44);
        }
        startInstall(a,operation);
    }
    private void startInstall(ApkRepository.Apk apk,String operation) {
        toast("Preparing Android installation…");
        io.execute(()->{
            try {Installer.install(getApplicationContext(),apk,operation);}
            catch(Exception ex){runOnUiThread(()->toast("Install preparation failed: "+ex.getMessage()));}
            runOnUiThread(this::refresh);
        });
    }
    private void offerReinstallFromHistory(String id) {
        if(id.equals(lastOutcomeDialog))return;
        JSONObject log=HistoryStore.get(this,id);
        if(log==null)return;
        String pkg=log.optString("package");
        // An app must not uninstall itself as part of automatic conflict recovery:
        // that would lose its own state and interrupt the replacement sequence.
        if (getPackageName().equals(pkg)) {
            toast("To replace AppManager itself, use Android's installer manually.");
            return;
        }
        if (!ApkRepository.installed(this,pkg)) return;
        lastOutcomeDialog=id;
        new AlertDialog.Builder(this)
                .setTitle("Update conflict — reinstall?")
                .setMessage(pkg+" could not update. Uninstalling the existing app may permanently erase its local app data, profiles and settings. " +
                        "The new APK is retained until installation succeeds. Continue?")
                .setNegativeButton("Keep existing app",(d,w)->{lastOutcomeDialog="";})
                .setPositiveButton("Uninstall then reinstall",(d,w)->{
                    Uri source=Uri.parse(log.optString("uri"));
                    io.execute(()->{
                        ApkRepository.Apk apk=repository.inspect(getApplicationContext(),source,
                                log.optString("apk"),-1);
                        runOnUiThread(()->{
                            if(!apk.valid() || !pkg.equals(apk.pkg)) {
                                toast("Retained APK missing or has a different package. No uninstall performed.");return;
                            }
                            pendingReinstall=apk;
                            getSharedPreferences("settings",MODE_PRIVATE).edit()
                                    .putString("pending_reinstall_id",id).apply();
                            try {
                                Intent remove=new Intent(Intent.ACTION_UNINSTALL_PACKAGE,
                                        Uri.parse("package:"+pkg));
                                remove.putExtra(Intent.EXTRA_RETURN_RESULT,true);
                                startActivityForResult(remove,UNINSTALL);
                            } catch (Exception ex) {
                                pendingReinstall=null;
                                getSharedPreferences("settings",MODE_PRIVATE).edit()
                                        .remove("pending_reinstall_id").apply();
                                toast("Could not request uninstall: "+ex.getMessage());
                            }
                        });
                    });
                }).show();
    }
    private void checkInterruptedReinstall() {
        String id=getSharedPreferences("settings",MODE_PRIVATE)
                .getString("pending_reinstall_id","");
        if (id.isEmpty() || id.equals(lastRecoveryPrompt) || pendingReinstall!=null) return;
        JSONObject record=HistoryStore.get(this,id);
        if (record==null) return;
        String pkg=record.optString("package");
        if (ApkRepository.installed(this,pkg)) return; // uninstall not completed
        lastRecoveryPrompt=id;
        new AlertDialog.Builder(this).setTitle("Complete interrupted reinstall?")
                .setMessage(pkg+" was uninstalled but the APK was retained. Continue installation now?")
                .setNegativeButton("Not now",(d,w)->{lastRecoveryPrompt="";})
                .setPositiveButton("Install replacement",(d,w)->{
                    Uri source=Uri.parse(record.optString("uri"));
                    io.execute(()->{
                        ApkRepository.Apk a=repository.inspect(getApplicationContext(),source,
                                record.optString("apk"),-1);
                        runOnUiThread(()->{
                            if(a.valid() && pkg.equals(a.pkg)) {
                                getSharedPreferences("settings",MODE_PRIVATE).edit()
                                        .remove("pending_reinstall_id").apply();
                                requestInstall(a,"Recovered reinstall");
                            } else toast("Retained APK unavailable; manually select a replacement");
                        });
                    });
                }).show();
    }
    private void toast(String message) {
        Toast.makeText(this,message,Toast.LENGTH_LONG).show();
    }
}
