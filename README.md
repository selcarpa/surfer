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
    //Inbound(refer: inbound section)
  ],
  outbounds: [
    //Outbound(refer: outbound section)
  ],
  rules: [
    //Rule(refer: rule section)
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
  socks5Setting: {
    //optional
    auth: {
      //optional, if not set, no authentication
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
  //when inbound protocol is trojan, this field is required
  "inboundStreamBy": {
    //ws is only supported now
    "type": "ws",
    //when inboundStreamBy.type is ws, this field is required 
    "wsInboundSetting": {
      //ws path, must set
      "path": "/path"
    }
  },
  //when inbound protocol is trojan, this field is required
  "trojanSetting": {
    //password, must set
    "password": "password"
  }
}
```

#### Outbound

##### direct outbound

```json5
{
  protocol: "galaxy",
  //optional
  outboundStreamBy: {
    //http and socks5 are supported now
    type: "http",
    //when outboundStreamBy.type is http, this field is required
    httpOutboundSetting: {
      host: "127.0.0.1",
      port: 8080
    },
    //when outboundStreamBy.type is socks5, this field is required
    sock5OutboundSetting: {
      host: "127.0.0.1",
      port: 8080
    }
  }
}
```

#### Rule

```json5
{
  rules: [
    //RuleItem
    {
      //optional content and priority: tagged, sniffed
      type: "",
      //when type is tagged, tag is required
      tag: "",
      //when type is sniffed, protocol destPattern has at least one
      protocol: "",
      //regex pattern for destination address
      destPattern: "",
      //when rule has same type, the order is used to determine which rule to use 
      order: ""
    }
  ]
}
```

##### tagged

```json5
{
  type: "tagged",
  //tag name
  tag: ""
}
```

##### sniffed

```json5
{
  type: "sniffed",
  //optional, for examples: tcp, udp, http
  protocol: "",
  //optional, regex pattern for destination address
  destPattern: "",
}
```
