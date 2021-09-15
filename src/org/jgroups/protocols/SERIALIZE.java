package org.jgroups.protocols;

import org.jgroups.Address;
import org.jgroups.Event;
import org.jgroups.Message;
import org.jgroups.annotations.MBean;
import org.jgroups.stack.Protocol;
import org.jgroups.util.Buffer;
import org.jgroups.util.MessageBatch;
import org.jgroups.util.Util;

/**
 * Serializes the entire message (including payload, headers, flags and destination and src) into the payload of
 * another message that's then sent. Deserializes the payload of an incoming message into a new message that's sent
 * up the stack.<br/>
 * To be used with {@link ASYM_ENCRYPT} or {@link SYM_ENCRYPT} when the entire message (including the headers) needs to
 * be encrypted. Can be used as a replacement for the deprecated attribute encrypt_entire_message in the above encryption
 * protocols.<br/>
 * See https://issues.jboss.org/browse/JGRP-2273 for details.
 * @author Bela Ban
 * @since  4.0.12
 */
@MBean(description="Serializes entire message into the payload of another message")
public class SERIALIZE extends Protocol {
    //@Property(description="If true, messages with no payload will not be serialized")
    //protected boolean exclude_empty_msgs=true;
    protected Address local_addr;


    public Object down(Event evt) {
        switch(evt.getType()) {
            case Event.SET_LOCAL_ADDRESS:
                local_addr=evt.getArg();
                break;
        }
        return down_prot.down(evt);
    }


    public Object down(Message msg) {
        if(msg.getSrc() == null)
            msg.setSrc(local_addr);

        Buffer serialized_msg=Util.streamableToBuffer(msg);
        // exclude existing headers, they will be seen again when we unmarshal the message at the receiver
        Message tmp=msg.copy(false, false).setBuffer(serialized_msg);
        return down_prot.down(tmp);
    }

    public Object up(Message msg) {
        try {
            Message ret=deserialize(msg);
            return up_prot.up(ret);
        }
        catch(Exception e) {
            throw new RuntimeException(String.format("failed deserialize message from %s", msg.src()), e);
        }
    }

    public void up(MessageBatch batch) {
        for(Message msg: batch) {
            try {
                Message deserialized_msg=deserialize(msg);
                batch.replace(msg, deserialized_msg);
            }
            catch(Exception e) {
                log.error("failed deserializing message", e);
                batch.remove(msg);
            }
        }
        if(!batch.isEmpty())
            up_prot.up(batch);
    }


    protected static Message deserialize(Message msg) throws Exception {
        try {
            Message ret=Util.streamableFromBuffer(Message::new, msg.getRawBuffer(), msg.getOffset(), msg.getLength());
            if(ret.getDest() == null)
                ret.setDest(msg.getDest());
            if(ret.getSrc() == null)
                ret.setSrc(msg.getSrc());
            return ret;
        }
        catch(Exception e) {
            throw new Exception(String.format("failed deserialize message from %s", msg.src()), e);
        }
    }
}
