# surfer

surfer

The purpose is to use kotlin netty to implement some protocols supported by v2ray

## compile environment

- jdk 11

## config

```json5
{
  "inbounds": [
    {
      "port": 14270,
      "protocol": "socks5",
      "socks5Setting": {
        //optional 
        "auth": {
          "password": "1",
          "username": "1"
        }
      }
    },
    {
      "port": 14271,
      "protocol": "http"
    },
    {
      "protocol": "trojan",
      "port": 14272,
      "inboundStreamBy": {
        "type": "ws",
        "wsInboundSettings": [
          {
            "path": "/exam"
          }
        ]
      }
    }
  ],
  "outbounds": [
    {
      "protocol": "galaxy"
    },
    {
      "protocol": "trojan",
      "outboundStreamBy": {
        "type": "wss",
        "wsOutboundSettings": [
          {
            "port": 443,
            "host": "a.exampl.com",
            "path": "/exam"
          }
        ]
      },
      "trojanSetting": {
        "password": "any password"
      }
    }
  ]
}

```
