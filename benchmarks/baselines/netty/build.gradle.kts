plugins { java; application }
java { toolchain { languageVersion.set(JavaLanguageVersion.of(25)) } }
application { mainClass.set("hello.HelloWebServer") }
val nettyVersion = "4.2.19.Final"
dependencies {
    implementation("io.netty:netty-all:$nettyVersion")
    implementation("io.netty:netty-transport-native-epoll:$nettyVersion:linux-x86_64")
    implementation("io.netty:netty-transport-native-io_uring:$nettyVersion:linux-x86_64")
    implementation("com.jsoniter:jsoniter:0.9.23")
    implementation("org.javassist:javassist:3.33.0-GA")
}
