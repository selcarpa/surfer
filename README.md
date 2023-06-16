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
    //supported level(ignore case): OFF, ERROR, WARN, INFO, DEBUG, TRACE,
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
    //Inbound (refer: inbound section)
  ],
  outbounds: [
    //Outbound
  ],
  rules: [
    //Rule
  ]
}
```

#### Inbound

##### http inbound

```json5
{
  //listen port
  port: 14270,
  //protocol
  protocol: "http"
}
```

##### socks inbound

```json5
{
  port: 14270,
  protocol: "socks5",
  socks5Setting: {//optional
    auth: {//optional, if not set, no authentication
      username: "username",
      password: "password"
    }
  }
}
```

##### trojan inbound

```json5
  {
  "protocol": "trojan",
  "port": 14270,
  "inboundStreamBy": {//when inbound protocol is trojan, this field is required
    "type": "ws",//ws is only supported now
    "wsInboundSetting": {//when inboundStreamBy.type is ws, this field is required 
      "path": "/path"//ws path, must set
    }
  },
  "trojanSettings": [//when inbound protocol is trojan, this field is required
    {//1 item at least
      "password": "password"//password, must set
    }
  ]
}
```

#### Outbound

##### direct outbound

```json5
{
  protocol: "galaxy",
  outboundStreamBy: {
    //optional
    type: "http",//http and socks5 are supported now
    httpOutboundSetting: {//when outboundStreamBy.type is http, this field is required
      host: "127.0.0.1",
      port: 8080
    },
    sock5OutboundSetting: {//when outboundStreamBy.type is socks5, this field is required
      host: "127.0.0.1",
      port: 8080
    }
  }
}
```
