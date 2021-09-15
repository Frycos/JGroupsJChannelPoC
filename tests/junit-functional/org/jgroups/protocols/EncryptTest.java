package org.jgroups.protocols;

import org.jgroups.*;
import org.jgroups.conf.ClassConfigurator;
import org.jgroups.demos.KeyStoreGenerator;
import org.jgroups.protocols.pbcast.GMS;
import org.jgroups.protocols.pbcast.NAKACK2;
import org.jgroups.protocols.pbcast.NakAckHeader2;
import org.jgroups.stack.ProtocolStack;
import org.jgroups.util.Buffer;
import org.jgroups.util.ByteArrayDataOutputStream;
import org.jgroups.util.MyReceiver;
import org.jgroups.util.Util;
import org.testng.annotations.Test;

import javax.crypto.SecretKey;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Base class for tests {@link SYM_ENCRYPT_Test} and {@link ASYM_ENCRYPT_Test}
 * @author Bela Ban
 * @since  4.0
 */
@Test(groups={Global.FUNCTIONAL,Global.ENCRYPT},singleThreaded=true)
public abstract class EncryptTest {
    protected JChannel                 a,b,c,d,rogue;
    protected MyReceiver<Message>      ra, rb, rc, r_rogue;
    protected final String             cluster_name=getClass().getSimpleName();
    protected static final short       GMS_ID;

    static {
        GMS_ID=ClassConfigurator.getProtocolId(GMS.class);
    }


    protected void init() throws Exception {
        a=create("A").connect(cluster_name).setReceiver(ra=new MyReceiver<>().rawMsgs(true));
        b=create("B").connect(cluster_name).setReceiver(rb=new MyReceiver<>().rawMsgs(true));
        c=create("C").connect(cluster_name).setReceiver(rc=new MyReceiver<>().rawMsgs(true));
        Util.waitUntilAllChannelsHaveSameView(10000, 500, a, b, c);
        rogue=createRogue("rogue").connect(cluster_name);
        Stream.of(a,b,c,rogue).forEach(ch -> System.out.printf("%s: %s\n", ch.getAddress(), ch.getView()));
        System.out.println("");
    }

    protected void destroy() {Util.close(rogue,d,c,b,a);}

    protected abstract JChannel create(String name) throws Exception;


    /** Tests A,B or C sending messages and their reception by everyone in cluster {A,B,C} */
    //@Test(groups=Global.FUNCTIONAL,singleThreaded=true)
    public void testRegularMessageReception() throws Exception {
        a.send(null, "Hello from A");
        b.send(null, "Hello from B");
        c.send(null, "Hello from C");
        for(int i=0; i < 10; i++) {
            if(ra.size() == 3 && rb.size() == 3 && rc.size() == 3)
                break;
            Util.sleep(500);
        }
        Stream.of(ra, rb, rc).map(MyReceiver::list).map(l -> l.stream().map(msg -> (String)msg.getObject())
          .collect(ArrayList::new, ArrayList::add, (x, y) -> {})).forEach(System.out::println);
        assertForEachReceiver(r -> r.size() == 3);
    }

    /** Same as above, but all message payloads are null */
    public void testRegularMessageReceptionWithNullMessages() throws Exception {
        a.send(new Message(null));
        b.send(new Message(null));
        c.send(new Message(null));
        for(int i=0; i < 10; i++) {
            if(ra.size() == 3 && rb.size() == 3 && rc.size() == 3)
                break;
            Util.sleep(500);
        }
        Stream.of(ra, rb, rc).map(MyReceiver::list).map(l -> l.stream().map(msg -> (String)msg.getObject())
          .collect(ArrayList::new, ArrayList::add, (x, y) -> {})).forEach(System.out::println);
        assertForEachReceiver(r -> r.size() == 3);
        assertForEachMessage(msg -> msg.getRawBuffer() == null);
    }

    /** Same as above, but all message payloads are empty (0-length String) */
    public void testRegularMessageReceptionWithEmptyMessages() throws Exception {
        a.send(new Message(null).setBuffer(new byte[0]));
        b.send(new Message(null).setBuffer(new byte[0]));
        c.send(new Message(null).setBuffer(new byte[0]));
        for(int i=0; i < 10; i++) {
            if(ra.size() == 3 && rb.size() == 3 && rc.size() == 3)
                break;
            Util.sleep(500);
        }
        assertForEachReceiver(r -> r.size() == 3);
        assertForEachMessage(msg -> msg.getLength() == 0);
        assertForEachMessage(msg -> Arrays.equals(msg.getRawBuffer(), new byte[0]));
    }


    /** A rogue member should not be able to join a cluster */
    //@Test(groups=Global.FUNCTIONAL,singleThreaded=true)
    public void testRogueMemberJoin() throws Exception {
        Util.close(rogue);
        rogue=new JChannel(Util.getTestStack()).name("rogue");
        rogue.getProtocolStack().removeProtocol(Encrypt.class);
        GMS gms=rogue.getProtocolStack().findProtocol(GMS.class);
        gms.setMaxJoinAttempts(1);
        try {
            rogue.connect(cluster_name);
        }
        catch(Exception ex) {}
        for(int i=0; i < 10; i++) {
            if(a.getView().size() > 3)
                break;
            Util.sleep(500);
        }
        Arrays.asList(a,b,c).forEach(ch -> System.out.printf("%s: view is %s\n", ch.getAddress(), ch.getView()));
        Arrays.asList(a,b,c).forEach(ch -> {
            View view=ch.getView();
            assert view.size() == 3 : "view should be {A,B,C}: " + view;
        });
    }


    /** Test that A,B,C do NOT receive any message sent by a rogue node which is not member of {A,B,C} */
    //@Test(groups=Global.FUNCTIONAL,singleThreaded=true)
    public void testMessageSendingByRogue() throws Exception {
        rogue.send(null, "message from rogue");  // tests single messages
        Util.sleep(500);
        for(int i=1; i <= 100; i++)              // tests message batches
            rogue.send(null, "msg #" + i + " from rogue");

        for(int i=0; i < 10; i++) {
            if(ra.size() > 0 || rb.size() > 0 || rc.size() > 0)
                break;
            Util.sleep(500);
        }
        assert ra.size() == 0 : String.format("received msgs from non-member: '%s'; this should not be the case", print(ra.list()));
        assert rb.size() == 0 : String.format("received msgs from non-member: '%s'; this should not be the case", print(rb.list()));
        assert rc.size() == 0 : String.format("received msgs from non-member: '%s'; this should not be the case", print(rc.list()));
    }


    /**
     * R sends a message that has an encryption header and is encrypted with R's secret key (which of course is different
     * from the cluster members' shared key as R doesn't know it). The cluster members should drop R's message as they
     * shouldn't be able to decrypt it.
     */
    //@Test(groups=Global.FUNCTIONAL,singleThreaded=true)
    public void testMessageSendingByRogueUsingEncryption() throws Exception {
        SYM_ENCRYPT encrypt=new SYM_ENCRYPT().keystoreName("/tmp/ignored.keystore");

        SecretKey secret_key=KeyStoreGenerator.createSecretKey();
        Field secretKey=Util.getField(SYM_ENCRYPT.class, "secret_key");
        secretKey.setAccessible(true);
        Util.setField(secretKey, encrypt, secret_key);
        encrypt.init();

        short encrypt_id=ClassConfigurator.getProtocolId(SYM_ENCRYPT.class);
        EncryptHeader hdr=new EncryptHeader(encrypt.symVersion());
        Message msg=new Message(null).putHeader(encrypt_id, hdr);

        byte[] buf="hello from rogue".getBytes();
        byte[] encrypted_buf=encrypt.code(buf, 0, buf.length, false);
        msg.setBuffer(encrypted_buf);

        rogue.send(msg);

        for(int i=0; i < 10; i++) {
            if(ra.size() > 0 || rb.size() > 0 || rc.size() > 0)
                break;
            Util.sleep(500);
        }
        assert ra.size() == 0 : String.format("received msgs from non-member: '%s'; this should not be the case", print(ra.list()));
        assert rb.size() == 0 : String.format("received msgs from non-member: '%s'; this should not be the case", print(rb.list()));
        assert rc.size() == 0 : String.format("received msgs from non-member: '%s'; this should not be the case", print(rc.list()));
    }


    /**
     * Tests that the non-member does NOT receive messages from cluster {A,B,C}. The de-serialization of a message's
     * payload (encrypted with the secret key of the rogue non-member) will fail, so the message is never passed up
     * to the application.
     */
    //@Test(groups=Global.FUNCTIONAL,singleThreaded=true)
    public void testMessageReceptionByRogue() throws Exception {
        rogue.setReceiver(r_rogue=new MyReceiver().rawMsgs(true));
        a.setReceiver(null); b.setReceiver(null); c.setReceiver(null);
        a.send(null, "Hello from A");
        b.send(null, "Hello from B");
        c.send(null, "Hello from C");
        for(int i=0; i < 10; i++) {
            // retransmissions will add dupes to rogue as it doesn't have dupe elimination, so we could have more than
            // 3 messages!
            if(r_rogue.size() > 0)
                break;
            Util.sleep(500);
        }

        // the non-member may have received some cluster messages, if the encrypted messages coincidentally didn't
        // cause a deserialization exception, but it will not be able to read their contents:
        if(r_rogue.size() > 0) {
            System.out.printf("Rogue non-member received %d message(s), but it should not be able to read deserialize " +
                                "the contents (this should throw exceptions below):\n", r_rogue.size());
            r_rogue.list().forEach(msg -> {
                try {
                    String payload=msg.getObject();
                    assert !payload.startsWith("Hello from");
                }
                catch(Exception t) {
                    System.out.printf("caught exception trying to de-serialize garbage payload into a string: %s\n", t);
                }
            });
        }
    }


    /**
     * Tests the scenario where the non-member R captures a message from some cluster member in {A,B,C}, then
     * increments the NAKACK2 seqno and resends that message. The message must not be received by {A,B,C};
     * it should be discarded. see https://issues.jboss.org/browse/JGRP-2273
     */
    public void testCapturingOfMessageByNonMemberAndResending() throws Exception {

        // add SERIALIZE over the encryption protocol, so headers are encrypted, too, and therefore a replay attack as
        // this one won't succeed
        for(JChannel ch: Arrays.asList(a,b,c)) {
            ch.getProtocolStack().insertProtocol(new SERIALIZE(), ProtocolStack.Position.ABOVE, Encrypt.class);
        }


        rogue.setReceiver(msg -> {
            System.out.printf("rogue: modifying and resending msg %s, hdrs: %s\n", msg, msg.printHeaders());
            rogue.setReceiver(null); // to prevent recursive cycle
            try {
                short prot_id=ClassConfigurator.getProtocolId(NAKACK2.class);
                NakAckHeader2 hdr=msg.getHeader(prot_id);
                if(hdr != null) {
                    long seqno=hdr.getSeqno();
                    Util.setField(Util.getField(NakAckHeader2.class, "seqno"), hdr, seqno+1);
                }
                else {
                    System.out.printf("Rogue was not able to get the %s header, fabricating one with seqno=50\n", NAKACK2.class.getSimpleName());
                    NakAckHeader2 hdr2=NakAckHeader2.createMessageHeader(50);
                    msg.putHeader(prot_id, hdr2);
                }

                rogue.send(msg);
            }
            catch(Exception e) {
                e.printStackTrace();
            }
        });

        a.send(null, "Hello world from A");

        // everybody in {A,B,C} should receive this message, but NOT the rogue's resent message
        for(int i=0; i < 10; i++) {
            if(ra.size() > 1 || rb.size() > 1 || rc.size() > 1)
                break; // this should NOT happen
            Util.sleep(500);
        }

        Stream.of(ra, rb, rc).map(MyReceiver::list).map(l -> l.stream().map(msg -> (String)msg.getObject())
          .collect(Collectors.toList())).forEach(System.out::println);
        assert ra.size() == 1 : String.format("received msgs from non-member: '%s'; this should not be the case", print(ra.list()));
        assert rb.size() == 1 : String.format("received msgs from non-member: '%s'; this should not be the case", print(rb.list()));
        assert rc.size() == 1 : String.format("received msgs from non-member: '%s'; this should not be the case", print(rc.list()));
    }



    /**
     * Tests the case where a non-member installs a new view {rogue,A,B,C}, making itself the coordinator and therefore
     * controlling admission of new members to the cluster etc...
     */
    //@Test(groups=Global.FUNCTIONAL,singleThreaded=true)
    public void testRogueViewInstallation() throws Exception {
        final Address rogue_addr=rogue.getAddress();
        View rogue_view=View.create(rogue_addr, a.getView().getViewId().getId()+1,
                                    rogue_addr, a.getAddress(), b.getAddress(), c.getAddress());

        Message view_change_msg=new Message().putHeader(GMS_ID, new GMS.GmsHeader(GMS.GmsHeader.VIEW))
          .setBuffer(marshal(rogue_view));
        rogue.send(view_change_msg);

        for(int i=0; i < 10; i++) {
            if(a.getView().size() > 3)
                break;
            Util.sleep(500);
        }
        Arrays.asList(a,b,c).forEach(ch -> {
            View view=ch.getView();
            System.out.printf("%s: view is %s\n", ch.getAddress(), view);
            assert !view.containsMember(rogue_addr) : "view contains rogue member: " + view;
        });
    }


    protected static JChannel createRogue(String name) throws Exception {
        return new JChannel(new SHARED_LOOPBACK()).name(name);
    }


    protected static Buffer marshal(final View view) throws Exception {
        ByteArrayDataOutputStream out=new ByteArrayDataOutputStream(Util.size(view));
        out.writeShort(1);
        if(view != null)
            view.writeTo(out);
        return out.getBuffer();
    }

    protected void assertForEachReceiver(Predicate<MyReceiver<Message>> predicate) {
        Stream.of(ra, rb, rc).forEach(receiver -> {assert predicate.test(receiver);});
    }

    protected void assertForEachMessage(Predicate<Message> predicate) {
        Stream.of(ra, rb, rc)
          .map(MyReceiver::list)
          .flatMap(Collection::stream)
          .forEach(msg -> {assert predicate.test(msg);});
    }

    protected static String print(List<Message> msgs) {
        return msgs.stream().map(Message::getObject).map(obj -> obj== null? "null" : obj.toString())
          .collect(Collectors.joining(", ", "[", "]"));
    }

    protected static String print(byte[] buf, int offset, int length) {
        StringBuilder sb=new StringBuilder("encrypted string: ");
        for(int i=0; i < length; i++) {
            int ch=buf[offset+i];
            sb.append(ch).append(' ');
        }
        return sb.toString();
    }


}
