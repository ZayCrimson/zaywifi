package com.zaywifi.app;

import android.content.Context;
import java.io.*; import java.security.SecureRandom; import java.util.*;

public final class VoucherManager {
  private static final String ALPHABET="ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
  private final File file; private final SecureRandom random=new SecureRandom();
  public VoucherManager(Context c){ File d=new File(c.getFilesDir(),"data"); d.mkdirs(); file=new File(d,"vouchers.txt"); }
  public synchronized List<Voucher> list(){ List<Voucher> r=new ArrayList<>(); try(BufferedReader br=new BufferedReader(new FileReader(file))){String l; while((l=br.readLine())!=null){String[] p=l.split("\\|",-1); if(p.length>0&&!p[0].trim().isEmpty()) r.add(new Voucher(p[0].trim(),p.length>1?p[1].trim():""));}}catch(Exception ignored){} return r; }
  public synchronized String createAuto(){ String c; do{c=randomCode(6);}while(find(c)!=null); append(c,""); return c; }
  public synchronized String createCustom(String c){c=normalize(c); if(c.length()<4||c.length()>32||!c.matches("[A-Z0-9-]+"))throw new IllegalArgumentException("Kode 4-32 karakter, hanya A-Z, 0-9, -"); if(find(c)!=null)throw new IllegalArgumentException("Voucher sudah ada"); append(c,""); return c;}
  public synchronized void delete(String code){rewrite(code,false);}
  public synchronized void reset(String code){rewrite(code,true);}
  public synchronized Voucher find(String code){String n=normalize(code); for(Voucher v:list())if(v.code.equals(n))return v; return null;}
  public synchronized void bind(String code,String ip){List<Voucher> vs=list(); try(PrintWriter w=new PrintWriter(new FileWriter(file,false))){for(Voucher v:vs){if(v.code.equals(normalize(code)))v.ip=ip; w.println(v.code+"|"+v.ip);}}catch(IOException e){throw new RuntimeException(e);}}
  private void append(String c,String ip){try(PrintWriter w=new PrintWriter(new FileWriter(file,true))){w.println(c+"|"+ip);}catch(IOException e){throw new RuntimeException(e);}}
  private void rewrite(String code,boolean reset){String n=normalize(code); List<Voucher> vs=list(); boolean found=false; try(PrintWriter w=new PrintWriter(new FileWriter(file,false))){for(Voucher v:vs){if(v.code.equals(n)){found=true;if(reset)v.ip="";else continue;}w.println(v.code+"|"+v.ip);}}catch(IOException e){throw new RuntimeException(e);} if(!found)throw new IllegalArgumentException("Voucher tidak ditemukan");}
  private String normalize(String s){return s==null?"":s.trim().toUpperCase(Locale.US).replaceAll("\\s+","");}
  private String randomCode(int n){StringBuilder s=new StringBuilder(n);for(int i=0;i<n;i++)s.append(ALPHABET.charAt(random.nextInt(ALPHABET.length())));return s.toString();}
  public static final class Voucher { public String code,ip; Voucher(String c,String i){code=c;ip=i;} public boolean used(){return !ip.isEmpty();} }
}
