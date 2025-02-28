# surfer

surfer

The purpose is to use kotlin netty to implement some protocols supported by v2ray

## contribute

### compile environment

- jdk 21

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
  protocol: "http",
  //optional, if not set, surfer only listen on "127.0.0.1"
  listen:"127.0.0.1"
}
```

##### socks inbound

```json5
{
  port: 14270,
  protocol: "socks5",
  //optional, if not set, surfer only listen on "127.0.0.1"
  listen:"127.0.0.1",
  //optional, if there is no settings, we think of it as one none-auth socks5Setting in the list
  socks5Settings: [
    {
      //optional
      auth: {
        //optional, if not set, no authentication
        username: "username",
        password: "password"
      },
      //optional
      tag: ""
    }
  ]
}
```

##### trojan inbound

```json5
{
  "protocol": "trojan",
  "port": 14270,
  //optional, if not set, surfer only listen on "127.0.0.1"
  listen:"127.0.0.1",
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
  "trojanSettings": [
    {
      //password, must set
      "password": "password",
      //optional
      "tag": ""
    }
  ]
}
```

#### Outbound

##### direct outbound

```json5
{
  protocol: "galaxy",
  //optional
  tag: ""
}
```

##### direct outbound by http proxy

```json5
{
  protocol: "http",
  httpSetting: {
    host: "",
    port: 20809,
    //optional
    auth: {
      //optional, if not set, no authentication
      username: "username",
      password: "password"
    },
  },
  //optional
  tag: ""
}
```
##### direct outbound by socks5 proxy

```json5
{
  protocol: "socks5",
  socks5Setting: {
    host: "",
    port: 20808,
    //optional
    auth: {
      //optional, if not set, no authentication
      username: "username",
      password: "password"
    },
  },
  //optional
  tag: ""
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
## future plan

- fullcone, stun support
- socks5 udp support
- reverse proxy support
- kcp support
