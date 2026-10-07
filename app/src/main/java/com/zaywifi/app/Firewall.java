package com.zaywifi.app;

public final class Firewall {
  private Firewall(){}
  public static Root.Result setup(String gateway,int port){
    String c="iptables -N ZAY_CAPTIVE 2>/dev/null; iptables -F ZAY_CAPTIVE; iptables -D FORWARD -j ZAY_CAPTIVE 2>/dev/null; iptables -I FORWARD 1 -j ZAY_CAPTIVE; iptables -A ZAY_CAPTIVE -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT; iptables -A ZAY_CAPTIVE -p udp --dport 53 -j ACCEPT; iptables -A ZAY_CAPTIVE -p udp --sport 53 -j ACCEPT; iptables -t nat -N ZAY_CAPTIVE_NAT 2>/dev/null; iptables -t nat -F ZAY_CAPTIVE_NAT; iptables -t nat -D PREROUTING -j ZAY_CAPTIVE_NAT 2>/dev/null; iptables -t nat -I PREROUTING 1 -j ZAY_CAPTIVE_NAT; iptables -t nat -A ZAY_CAPTIVE_NAT -p tcp --dport 80 -j REDIRECT --to-port "+port+"; iptables -P FORWARD DROP";
    return Root.run(c);
  }
  public static Root.Result allow(String ip){return Root.run("iptables -D ZAY_CAPTIVE -s "+q(ip)+" -j ACCEPT 2>/dev/null; iptables -I ZAY_CAPTIVE 1 -s "+q(ip)+" -j ACCEPT; iptables -t nat -D ZAY_CAPTIVE_NAT -s "+q(ip)+" -j RETURN 2>/dev/null; iptables -t nat -I ZAY_CAPTIVE_NAT 1 -s "+q(ip)+" -j RETURN");}
  public static Root.Result revoke(String ip){return Root.run("iptables -D ZAY_CAPTIVE -s "+q(ip)+" -j ACCEPT 2>/dev/null; iptables -t nat -D ZAY_CAPTIVE_NAT -s "+q(ip)+" -j RETURN 2>/dev/null");}
  public static Root.Result cleanup(){return Root.run("iptables -P FORWARD ACCEPT 2>/dev/null; iptables -D FORWARD -j ZAY_CAPTIVE 2>/dev/null; iptables -t nat -D PREROUTING -j ZAY_CAPTIVE_NAT 2>/dev/null; iptables -F ZAY_CAPTIVE 2>/dev/null; iptables -X ZAY_CAPTIVE 2>/dev/null; iptables -t nat -F ZAY_CAPTIVE_NAT 2>/dev/null; iptables -t nat -X ZAY_CAPTIVE_NAT 2>/dev/null");}
  private static String q(String s){if(s==null||!s.matches("[0-9.]+"))throw new IllegalArgumentException("IP tidak valid");return s;}
}
