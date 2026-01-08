ThisBuild / scalaVersion     := "2.13.14"
ThisBuild / version          := "0.1.0-SNAPSHOT"
ThisBuild / organization     := "cn.xuyinyin"
ThisBuild / organizationName := "mgaic"

// ================================
// 版本定义 - 统一管理所有依赖版本
// ================================
val scalaVersion213 = "2.13.14"
val projectVersion  = "0.1"
val projectName     = "xxt-cdc"
val projectOrg      = "cn.xuyinyin"

// Pekko 生态系统
val pekkoVersion       = "1.1.3"
val pekkoHttpVersion   = "1.0.1"
val pekkoConnectorsVer = "1.0.2"

// 数据处理和存储
val arrowVersion      = "18.0.0"  // Arrow Flight for DataFusion integration
val calciteVersion    = "1.39.0"  // SQL parser
val levelDbVersion    = "1.8"     // Event Sourcing storage
val levelDbApiVersion = "0.12"
val h2Version         = "2.3.232" // Test database

// JSON和序列化
val jacksonVersion   = "2.17.2" // 统一Jackson版本，避免冲突
val sprayJsonVersion = "1.3.6"

// 日志系统
val logbackVersion      = "1.4.12"
val scalaLoggingVersion = "3.9.5"

// 监控和指标
val prometheusVersion = "0.16.0"

// 测试框架
val scalatestVersion     = "3.2.19" // 统一ScalaTest版本
val scalacheckVersion    = "1.17.0"
val scalatestPlusVersion = "3.2.17.0"

// 网络和RPC
val grpcVersion = "1.70.0"

// 工具库
val commonsPoolVersion = "2.12.0"

// MySQL 连接器
val mysqlConnectorVersion = "8.0.33"
val mysqlBinlogVersion    = "0.29.2"
val hikariCPVersion       = "5.1.0"

// ================================
// 通用设置 - 所有模块共享的配置
// ================================
lazy val commonSettings = Seq(
  name       := projectName,

  // ================================
  // Scala编译器选项 - 启用严格检查
  // ================================
  Compile / scalacOptions ++= Seq(
    "-deprecation",           // 显示弃用警告
    "-feature",               // 显示特性警告
    "-unchecked",             // 显示未检查警告
    "-Xlog-reflective-calls", // 记录反射调用
    "-Xlint"                  // 启用所有lint检查
  ),

  // ================================
  // Java编译器选项
  // ================================
  Compile / javacOptions ++= Seq(
    "-Xlint:unchecked",
    "-Xlint:deprecation",
    "-source",
    "11",
    "-target",
    "11"
  ),

  // ================================
  // 运行时JVM配置
  // ================================
  run / javaOptions ++= Seq(
    // 内存配置
    "-Xms128m",
    "-Xmx1024m",

    // 本地库路径
    "-Djava.library.path=./target/native",

    // Pekko集群种子节点
    "-Dpekko.cluster.seed-nodes.0=pekko://pekko-cluster-system@127.0.0.1:2551",

    // GC日志配置 - 用于性能分析和调优
    "-Xlog:gc*:file=logs/gc.log:time,uptime,level,tags",
    "-Xlog:gc+heap=trace:file=logs/gc-heap.log:time,uptime",
    "-Xlog:gc+ref=debug:file=logs/gc-ref.log:time,uptime",
    "-XX:+UseGCLogFileRotation",
    "-XX:NumberOfGCLogFiles=5",
    "-XX:GCLogFileSize=10M"
  ),

  // ================================
  // 通用依赖库
  // ================================
  libraryDependencies ++= Seq(
    // 日志系统
    "com.typesafe.scala-logging" %% "scala-logging"   % scalaLoggingVersion,
    "ch.qos.logback"              % "logback-classic" % logbackVersion,

    // MySQL 连接器
    "com.mysql"        % "mysql-connector-j"            % mysqlConnectorVersion,
    "com.zendesk"      % "mysql-binlog-connector-java"  % mysqlBinlogVersion,
    "com.zaxxer"       % "HikariCP"                     % hikariCPVersion,

    // 测试框架
    "org.scalatest" %% "scalatest" % scalatestVersion % Test
  )
)

// ================================
// 根项目配置 - 简化版本
// ================================
lazy val root = (project in file("."))
  .settings(commonSettings)
  .settings(
    name         := projectName,
    publish      := {}, // 根项目不发布
    publishLocal := {}, // 根项目不本地发布

    // ================================
    // 应用程序配置
    // ================================
    Compile / mainClass := Some("cn.xuyinyin.cdc.CDCApplication"),

    // ================================
    // 依赖库配置
    // ================================
    libraryDependencies ++= Seq(
      // ================================
      // Pekko 核心依赖
      // ================================
      "org.apache.pekko" %% "pekko-actor-typed"            % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-typed"          % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-sharding-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-cluster-tools"          % pekkoVersion,
      "org.apache.pekko" %% "pekko-serialization-jackson"  % pekkoVersion,
      "org.apache.pekko" %% "pekko-stream"                 % pekkoVersion,

      // ================================
      // Pekko HTTP - API 服务
      // ================================
      "org.apache.pekko" %% "pekko-http" % pekkoHttpVersion,

      // ================================
      // Pekko 持久化 - Event Sourcing
      // ================================
      "org.apache.pekko" %% "pekko-persistence-typed" % pekkoVersion,
      "org.apache.pekko" %% "pekko-persistence-query" % pekkoVersion,

      // LevelDB存储引擎
      "org.fusesource.leveldbjni" % "leveldbjni-all" % levelDbVersion,
      "org.iq80.leveldb"          % "leveldb"        % levelDbApiVersion,

      // ================================
      // Pekko Connectors - 数据集成
      // ================================
      "org.apache.pekko" %% "pekko-connectors-slick" % pekkoConnectorsVer,
      "org.apache.pekko" %% "pekko-connectors-csv"   % pekkoConnectorsVer,

      // ================================
      // JSON 处理
      // ================================
      "io.spray" %% "spray-json" % sprayJsonVersion,

      // Jackson显式依赖 - 确保版本一致性
      "com.fasterxml.jackson.core"    % "jackson-databind"     % jacksonVersion,
      "com.fasterxml.jackson.core"    % "jackson-core"         % jacksonVersion,
      "com.fasterxml.jackson.core"    % "jackson-annotations"  % jacksonVersion,
      "com.fasterxml.jackson.module" %% "jackson-module-scala" % jacksonVersion,

      // ================================
      // 监控和指标
      // ================================
      "io.prometheus" % "simpleclient" % prometheusVersion,
      "io.prometheus" % "simpleclient_httpserver" % prometheusVersion,
      "io.prometheus" % "simpleclient_hotspot" % prometheusVersion,

      // ================================
      // 连接池管理
      // ================================
      "org.apache.commons" % "commons-pool2" % commonsPoolVersion,

      // ================================
      // 测试依赖
      // ================================
      "org.apache.pekko"  %% "pekko-stream-testkit"      % pekkoVersion         % Test,
      "org.apache.pekko"  %% "pekko-multi-node-testkit"  % pekkoVersion         % Test,
      "org.apache.pekko"  %% "pekko-actor-testkit-typed" % pekkoVersion         % Test,
      "org.apache.pekko"  %% "pekko-persistence-testkit" % pekkoVersion         % Test,
      "org.apache.pekko"  %% "pekko-http-testkit"        % pekkoHttpVersion     % Test,
      "org.scalatest"     %% "scalatest"                 % scalatestVersion     % Test,
      "org.scalatestplus" %% "scalacheck-1-17"           % scalatestPlusVersion % Test,
      "org.scalacheck"    %% "scalacheck"                % scalacheckVersion    % Test,
      "com.h2database"     % "h2"                        % h2Version            % Test
    )
  )