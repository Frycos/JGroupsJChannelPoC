package org.jgroups.tests;

import org.jgroups.Global;
import org.jgroups.Message;
import org.jgroups.util.AverageMinMax;
import org.jgroups.util.MessageBatch;
import org.jgroups.util.Util;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/** Tests multiple producers adding multiple elements to the queue (message batch) and one of them becoming the single
 * consumer which removes elements for as long as possible, then terminating. The algorithm needs to ensure that there
 * aren't any elements left in the queue when all producers and the single consumer have terminated. The Pluscal code
 * for this algorithm is at https://github.com/belaban/pluscal/blob/master/MessageBatchDrainTest.tla.
 * @author Bela Ban
 * @since  4.0
 */
@Test(groups=Global.FUNCTIONAL,singleThreaded=true)
public class MessageBatchDrainTest {
    protected final Lock           lock=new ReentrantLock();
    protected final MessageBatch   batch=new MessageBatch(BATCH_SIZE);
    protected final AtomicInteger  counter=new AtomicInteger(0);
    protected final LongAdder      added=new LongAdder();
    protected final LongAdder      removed=new LongAdder();
    protected final LongAdder      num_removers=new LongAdder();
    protected final AverageMinMax  avg_removed=new AverageMinMax();
    protected final AverageMinMax  avg_remove_loops=new AverageMinMax();

    protected static final boolean RESIZE=false;
    protected static final int     BATCH_SIZE=200;

    public void testDraining() throws InterruptedException {
        MyThread[] threads=new MyThread[10];
        final CountDownLatch latch=new CountDownLatch(1);
        for(int i=0; i < threads.length; i++) {
            threads[i]=new MyThread(latch);
            threads[i].start();
        }
        latch.countDown();

        Util.sleep(5000);

        System.out.printf("\nStopping threads\n");
        for(MyThread thread: threads)
            thread.cancel();
        System.out.printf("done, joining threads\n");
        for(MyThread thread: threads)
            thread.join();

        System.out.printf("\ncounter=%d, added=%d, removed=%d, avg_removed=%s, avg_remove_loops=%s (removers=%d)\n",
                          counter.get(), added.sum(), removed.sum(), avg_removed, avg_remove_loops, num_removers.sum());

        assert added.sum() == removed.sum();
        assert counter.get() == 0;
        assert this.batch.isEmpty();
    }

    protected void add(Message msg) {
        int size=_add(msg);
        if(size > 0) {
            added.increment();
            drain();
        }
    }

    protected void add(MessageBatch mb) {
        int size=_add(mb);
        if(size > 0) {
            added.add(size);
            drain();
        }
    }

    protected void drain() {
        if(counter.getAndIncrement() == 0) {
            num_removers.increment();
            int cnt=0, removed_msgs, total_removed=0;
            do {
                removed_msgs=_clear();
                total_removed+=removed_msgs;
                removed.add(removed_msgs);
                cnt++;
                // LockSupport.parkNanos(4_000);
            } while(counter.decrementAndGet() != 0);
            avg_remove_loops.add(cnt);
            avg_removed.add(total_removed);
        }
    }

    protected int _add(Message msg) {
        lock.lock();
        try {
            return this.batch.add(msg, RESIZE);
        }
        finally {
            lock.unlock();
        }
    }

    protected int _add(MessageBatch b) {
        lock.lock();
        try {
            return this.batch.add(b, RESIZE);
        }
        finally {
            lock.unlock();
        }
    }

    protected int _clear() {
        lock.lock();
        try {
            int size=batch.size();
            batch.clear();
            return size;
        }
        finally {
            lock.unlock();
        }
    }

    protected class MyThread extends Thread {
        protected final CountDownLatch latch;
        protected volatile boolean     running=true;

        public MyThread(CountDownLatch latch) {
            this.latch=latch;
        }

        protected void cancel() {running=false;}

        public void run() {
            try {
                latch.await();
            }
            catch(InterruptedException e) {
                e.printStackTrace();
            }
            while(running) {
                if(Util.tossWeightedCoin(.3))
                    add(new Message());
                else {
                    Message[] msgs=create(10);
                    MessageBatch mb=new MessageBatch(Arrays.asList(msgs));
                    add(mb);
                }
            }
        }
    }

    protected Message[] create(int max) {
        int num=(int)Util.random(max);
        Message[] msgs=new Message[num];
        for(int i=0; i < msgs.length; i++)
            msgs[i]=new Message();
        return msgs;
    }
}
