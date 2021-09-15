package org.jgroups.tests;

import org.jgroups.Message;
//import org.jgroups.util.RingBufferLocked;
import org.jgroups.util.RingBufferSeqno;
import org.jgroups.util.Util;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

public class RingBufferStressTest {
    static int NUM_THREADS=10;
    static int NUM_MSGS=1000000;

    static final Message MSG=new Message(false);


    static final AtomicInteger added=new AtomicInteger(0);
    static final AtomicInteger removed=new AtomicInteger(0);

    public static void main(String[] args) throws InterruptedException {
        for(int i=0; i < args.length; i++) {
            if(args[i].startsWith("-h")) {
                System.out.println("RingBufferStressTest [-num messages] [-adders <number of adder threads>]");
                return;
            }
            if(args[i].equals("-num")) {
                NUM_MSGS=Integer.parseInt(args[++i]);
                continue;
            }
            if(args[i].equals("-adders")) {
                NUM_THREADS=Integer.parseInt(args[++i]);
            }
        }

        RingBufferSeqno<Message> buf=new RingBufferSeqno<>(NUM_MSGS, 0);

        final CountDownLatch latch=new CountDownLatch(1);

        Remover remover=new Remover(buf,latch);
        remover.start();

        Adder[] adders=new Adder[NUM_THREADS];
        for(int i=0; i < adders.length; i++) {
            adders[i]=new Adder(buf,latch, added);
            adders[i].start();
        }

        Util.sleep(1000);

        long start=System.currentTimeMillis();
        latch.countDown();
        while(remover.isAlive()) {
            System.out.println("added messages: " + added + ", removed messages: " + removed);
            remover.join(2000);
        }
        long diff=System.currentTimeMillis() - start;

        System.out.println("added messages: " + added + ", removed messages: " + removed);
        System.out.println("took " + diff + " ms to insert and remove " + NUM_MSGS + " messages");
        buf.destroy();
    }


    protected static class Adder extends Thread {
        protected final RingBufferSeqno<Message> buf;
        protected final AtomicInteger num;
        protected final CountDownLatch latch;

        public Adder(RingBufferSeqno<Message> buf, CountDownLatch latch, AtomicInteger num) {
            this.buf=buf;
            this.num=num;
            this.latch=latch;
            setName("Adder");
        }

        public void run() {
            try {
                latch.await();
            }
            catch(InterruptedException e) {
                e.printStackTrace();
            }

            while(true) {
                int seqno=num.incrementAndGet();
                if(seqno > NUM_MSGS) {
                    num.decrementAndGet();
                    break;
                }
                buf.add(seqno, MSG, true);
            }
        }
    }

    protected static class Remover extends Thread {
        protected final RingBufferSeqno<Message> buf;
        protected final CountDownLatch latch;

        public Remover(RingBufferSeqno<Message> buf, CountDownLatch latch) {
            this.buf=buf;
            this.latch=latch;
            setName("Remover");
        }

        public void run() {
            try {
                latch.await();
            }
            catch(InterruptedException e) {
                e.printStackTrace();
            }
            int cnt=0;
            for(;;) {
                List<Message> msgs=buf.removeMany(true, 100);
                if(msgs != null) {
                    for(Message msg: msgs) {
                        cnt++;
                        removed.incrementAndGet();
                    }
                    continue;
                }
                if(cnt >= NUM_MSGS)
                    break;
                Util.sleep(500);
            }
            System.out.println("-- removed " + cnt + " messages");
        }
    }
}
