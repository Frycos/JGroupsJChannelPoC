# JGroups JChannel PoC

This repo is based on the original repo https://github.com/belaban/JGroups. Several known products use JGroups JChannel to enable clustering features. We modified the `src/org/jgroups/demos/RelayDemo.java` to read message content from a file, i.e. any chat message will send the file content to all cluster members.

1. Put your favorite RCE library in your classpath and create the first cluster member

`java -cp ../commons-collections-3.1.jar:target/jgroups-4.1.2.Final.jar org.jgroups.demos.RelayDemo`

2. Create your malicious serialized object and store it in `/tmp/pingme.ser`
3. Create the second cluster member and type any message you like

