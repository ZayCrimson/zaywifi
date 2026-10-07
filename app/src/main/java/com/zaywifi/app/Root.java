package com.zaywifi.app;

import java.io.*;
import java.util.*;

public final class Root {
  private Root() {}
  public static Result run(String command) {
    try {
      Process p = new ProcessBuilder("su", "-c", command).redirectErrorStream(true).start();
      String out = read(p.getInputStream());
      int code = p.waitFor();
      return new Result(code == 0, code, out);
    } catch (Exception e) { return new Result(false, -1, e.toString()); }
  }
  private static String read(InputStream in) throws IOException { byte[] b=new byte[8192]; int n; StringBuilder s=new StringBuilder(); while((n=in.read(b))!=-1)s.append(new String(b,0,n)); return s.toString(); }
  public static final class Result { public final boolean ok; public final int code; public final String output; Result(boolean o,int c,String s){ok=o;code=c;output=s;} }
}
