# surfer

surfer

The purpose is to use kotlin netty to implement some protocols supported by v2ray

## contribute

### compile environment

- jdk 11

## quick start

### run with arguments

```shell
java -jar surfer.jar -c=config.json
```

- -c=[config file path]

### config

```json5
{
  log: {
    //supported level(ignore case): OFF, ERROR, WARN, INFO, DEBUG, TRACE, ALL,
    level: "debug",
    //log format (refer: https://logback.qos.ch/manual/layouts.html#conversionWord)
    pattern: "%date{ISO8601} %level [%t] %logger{16} %M: %msg%n",
    //max days to keep log file
    maxHistory: 5,
    //file name
    fileName: "surfer",
    //path to output log file, absolute path or relative path to current directory
    path: "/var/log/surfer"
  },
  inbounds: [
    {
      port: 14270,
      protocol: "socks5",
      socks5Setting: {
        //optional 
        auth: {
          password: "1",
          username: "1"
        }
      }
    },
    {
      port: 14271,
      protocol: "http"
    },
    {
      protocol: "trojan",
      port: 14272,
      inboundStreamBy: {
        type: "ws",
        wsInboundSettings: [
          {
            path: "/exam"
          }
        ]
      }
    }
  ],
  outbounds: [
    {
      protocol: "galaxy"
    },
    {
      protocol: "trojan",
      outboundStreamBy: {
        type: "wss",
        wsOutboundSettings: [
          {
            port: 443,
            host: "a.exampl.com",
            path: "/exam"
          }
        ]
      },
      trojanSetting: {
        password: "any password"
      }
    }
  ]
}

```
