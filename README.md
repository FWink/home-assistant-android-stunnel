# home-assistant-android-stunnel
Contains a WebView that connects to a local Stunnel proxy (www.stunnel.org) to add client-side authentication (TLS PSK en.wikipedia.org/wiki/TLS-PSK) to HomeAssistant (www.home-assistant.io) (or any other web page)

# How it works
The app consists of a single activity hosting a WebView. The WebView connects to localhost where a Stunnel proxy (stunnel-client) is waiting to redirect traffic to yet another Stunnel proxy on the server (stunnel-server). The stunnel-server connects to a HomeAssistant server that may or may not be running on the same physical machine.

The traffic between the two stunnel proxies (i.e. the traffic going through the internet) is encrypted using TLS. Authentication is achieved by using PreSharedKeys (PSK): a symmetric key that is hardcoded both in this app and in the configuration of the stunnel-server. That is very simple to set up but very secure.
Traffic between WebView and stunnel-client and the traffic between stunnel-server and HomeAssistant server are plain text HTTP: both are connections to localhost anyway.

This allows us to have a HomeAssistant in a local network that is reachable from the Internet only via the secured stunnel-server (router setup allows traffic only to the stunnel-server, not the HomeAssistant). Since the stunnel-server **requires** authentication via PSK it is not (easily?) possible for anyone but owners of the PSKs to connect to the HomeAssistant.

# Usage
The app can't possibly know where your HomeAssistant server is located without being told so. That's why you need to add a config file 'config.json' in 'app/src/main/assets/config/' before building the app. Here's how that might look like:

```json
{
  "localPort": 12345,
  "remoteAddress": "google.com",
  "remotePort": 443,
  "preSharedKey": {
    "identity": "MyName",
    "key": "MyPreSharedKey"
  }
}
```
Usually you should use a FQDN as remoteAddress (for example using services such as www.noip.com).

## stunnel-server
Here's how a corresponding stunnel config for the stunnel-server would look like:

```
[https-hass-psk]
accept  = 443
connect = 8123
; "TIMEOUTclose = 0" is a workaround for a design flaw in Microsoft SChannel
; Microsoft implementations do not use TLS close-notify alert and thus they
; are vulnerable to truncation attacks
TIMEOUTclose = 0
sslVersion = TLSv1.2
ciphers = PSK
PSKsecrets = hass_psk.txt
```

And the PSKsecrets file 'hass_psk.txt' located in the same directory:
```
MyName:MyPreSharedKey
```

In this scenario the NAT in the local network is set up to redirect traffic received on port 443 (public) to port 443 (internal) on the machine running the stunnel-server. The HomeAssistant is running on the same machine: stunnel-server connects to localhost on port 8123 (the default HomeAssistant port).
