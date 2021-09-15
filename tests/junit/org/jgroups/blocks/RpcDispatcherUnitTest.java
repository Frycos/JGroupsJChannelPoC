package org.jgroups.blocks;


import org.jgroups.*;
import org.jgroups.tests.ChannelTestBase;
import org.jgroups.util.Rsp;
import org.jgroups.util.RspList;
import org.jgroups.util.Util;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeoutException;

/**
 * @author Bela Ban
 */
@Test(groups=Global.STACK_DEPENDENT,singleThreaded=true)
public class RpcDispatcherUnitTest extends ChannelTestBase {
    private RpcDispatcher d1, d2, d3;
    private JChannel      c1, c2, c3;
    private ServerObject  o1, o2, o3;
    private Address       a1, a2, a3;
    private List<Address> members;


    @BeforeClass
    protected void setUp() throws Exception {
        o1=new ServerObject();
        o2=new ServerObject();
        o3=new ServerObject();

        c1=createChannel(true, 3).setName("A");
        final String GROUP="RpcDispatcherUnitTest";
        d1=new RpcDispatcher(c1, o1);
        c1.connect(GROUP);

        c2=createChannel(c1).setName("B");
        d2=new RpcDispatcher(c2, o2);
        c2.connect(GROUP);

        c3=createChannel(c1).setName("C");
        d3=new RpcDispatcher(c3, o3);
        c3.connect(GROUP);

        Util.waitUntilAllChannelsHaveSameView(10000, 1000, c1, c2, c3);

        a1=c1.getAddress();
        a2=c2.getAddress();
        a3=c3.getAddress();
        members=Arrays.asList(a1, a2, a3);
    }

    @BeforeMethod
    protected void reset() {
        o1.reset();
        o2.reset();
        o3.reset();
    }

    @AfterClass
    protected void tearDown() throws Exception {
        Util.close(d3,d2,d1,c3, c2, c1);
    }


    public void testInvocationOnEntireGroup() throws Exception {
        RspList rsps=d1.callRemoteMethods(null, "foo", null, null, RequestOptions.SYNC());
        System.out.println("rsps:\n" + rsps);
        assert rsps.size() == 3;
        assert o1.wasCalled() && o2.wasCalled() && o3.wasCalled();
    }

    public void testInvocationOnEntireGroupWithTargetList() throws Exception {
        RspList rsps=d1.callRemoteMethods(members, "foo", null, null, RequestOptions.SYNC());
        System.out.println("rsps:\n" + rsps);
        assert rsps.size() == 3;
        assert o1.wasCalled() && o2.wasCalled() && o3.wasCalled();
    }


    /** Invoke a method on all but myself */
    public void testInvocationWithExclusionOfSelf() throws Exception {
        RequestOptions options=new RequestOptions(ResponseMode.GET_ALL, 5000).exclusionList(a1);
        RspList rsps=d1.callRemoteMethods(null, "foo", null, null, options);
        Util.sleep(500);
        System.out.println("rsps:\n" + rsps);
        assert rsps.size() == 2;
        assert rsps.containsKey(a2) && rsps.containsKey(a3);
        assert !o1.wasCalled() && o2.wasCalled() && o3.wasCalled();
    }

    public void testInvocationWithExclusionOfSelfUsingDontLoopback() throws Exception {
        RequestOptions options=new RequestOptions(ResponseMode.GET_ALL, 5000).transientFlags(Message.TransientFlag.DONT_LOOPBACK);
        RspList rsps=d1.callRemoteMethods(null, "foo", null, null, options);
        Util.sleep(500);
        System.out.println("rsps:\n" + rsps);
        assert rsps.size() == 2;
        assert rsps.containsKey(a2) && rsps.containsKey(a3);
        assert !o1.wasCalled() && o2.wasCalled() && o3.wasCalled();
    }

    public void testInvocationWithExclusionOfSelfUsingDontLoopbackAnycasting() throws Exception {
        RequestOptions options=new RequestOptions(ResponseMode.GET_ALL, 5000).transientFlags(Message.TransientFlag.DONT_LOOPBACK);
        RspList<Object> rsps=d1.callRemoteMethods(null, "foo", null, null, options.anycasting(true));
        Util.sleep(500);
        System.out.println("rsps:\n" + rsps);
        assert rsps.size() == 2;
        assert rsps.containsKey(a2) && rsps.containsKey(a3);
        assert !o1.wasCalled() && o2.wasCalled() && o3.wasCalled();
    }

    /** Invoke a method on all but myself and use DONT_LOOPBACK */
    public void testInvocationWithExclusionOfSelfWithDontLoopback() throws Exception {
        RequestOptions options=new RequestOptions(ResponseMode.GET_ALL, 5000).transientFlags(Message.TransientFlag.DONT_LOOPBACK);
        RspList rsps=d1.callRemoteMethods(null, "foo", null, null, options);
        Util.sleep(500);
        System.out.println("rsps:\n" + rsps);
        assert rsps.size() == 2;
        assert rsps.containsKey(a2) && rsps.containsKey(a3);
        assert o1.getNumCalls() == 0 && o2.getNumCalls() == 1 && o3.getNumCalls() == 1;

        rsps=d1.callRemoteMethods(Arrays.asList(a1,a2,a3), "foo", null, null, options);
        Util.sleep(500);
        System.out.println("rsps:\n" + rsps);
        assert rsps.size() == 2;
        assert rsps.containsKey(a2) && rsps.containsKey(a3);
        assert o1.getNumCalls() == 0 && o2.getNumCalls() == 2 && o3.getNumCalls() == 2;

        options.clearTransientFlags(Message.TransientFlag.DONT_LOOPBACK);
        rsps=d1.callRemoteMethods(Arrays.asList(a1,a2,a3), "foo", null, null, options);
        Util.sleep(500);
        System.out.println("rsps:\n" + rsps);
        assert rsps.size() == 3;
        assert rsps.containsKey(a1) && rsps.containsKey(a2) && rsps.containsKey(a3);
        assert o1.getNumCalls() == 1 && o2.getNumCalls() == 3 && o3.getNumCalls() == 3;
    }

    public void testInvocationWithExclusionOfSelfWithDontLoopbackUnicast() throws Exception {
        RequestOptions options=new RequestOptions(ResponseMode.GET_ALL, 500).transientFlags(Message.TransientFlag.DONT_LOOPBACK);
        try {
            d1.callRemoteMethod(a1,"foo",null,null,options);
        }
        catch(TimeoutException ex) {
            System.out.println("sending unicast to self with DONT_LOOPBACK threw exception as expected: " + ex);
        }
    }


    public void testInvocationWithExclusionOfTwo() throws Exception {
        RequestOptions options=new RequestOptions(ResponseMode.GET_ALL, 5000).exclusionList(a2, a3);
        RspList rsps=d1.callRemoteMethods(null, "foo", null, null, options);
        Util.sleep(500);
        System.out.println("rsps:\n" + rsps);
        assert rsps.size() == 1;
        assert rsps.containsKey(a1);
        assert o1.wasCalled() && !o2.wasCalled() && !o3.wasCalled();
    }

    public void testInvocationOnEmptyTargetSet() throws Exception {
        RequestOptions options=new RequestOptions(ResponseMode.GET_ALL, 5000).exclusionList(a1, a2, a3);
        RspList rsps=d1.callRemoteMethods(null, "foo", null, null, options);
        assert rsps != null && rsps.isEmpty();
        assert !o1.wasCalled() && !o2.wasCalled() && !o3.wasCalled();
    }


    public void testInvocationOfDefaultMethodInParentInterface() throws Exception {
        RspList<Integer> rsps=d1.callRemoteMethods(null, "bar", null, null, RequestOptions.SYNC());
        System.out.println("rsps:\n" + rsps);
        assert rsps.size() == 3;
        for(Rsp<Integer> rsp: rsps)
            assert rsp.getValue() == 22;
    }


    public void testInvocationOfDefaultMethod() throws Exception {
        RspList<Integer> rsps=d1.callRemoteMethods(null, "bar2", null, null, RequestOptions.SYNC());
        System.out.println("rsps:\n" + rsps);
        assert rsps.size() == 3;
        for(Rsp<Integer> rsp: rsps)
            assert rsp.getValue() == 44;
    }

    public void testInvocationOnSubclass() throws Exception {
        Object obj1=d1.server_obj, obj2=d2.server_obj, obj3=d3.server_obj;
        try {
            d1.server_obj=new Subclass();
            d2.server_obj=new Subclass();
            d3.server_obj=new Subclass();
            RspList<Integer> rsps=d1.callRemoteMethods(null, "foobar", null, null, RequestOptions.SYNC());
            System.out.println("rsps:\n" + rsps);
            assert rsps.size() == 3;
            for(Rsp<Integer> rsp : rsps)
                assert rsp.getValue() == 33;
        }
        finally {
            d1.server_obj=obj1;
            d2.server_obj=obj2;
            d3.server_obj=obj3;
        }
    }

    public void testInvocationOnObject() throws Exception {
        RspList<Integer> rsps=d1.callRemoteMethods(null, "hashCode", null, null, RequestOptions.SYNC());
        System.out.println("rsps:\n" + rsps);
        assert rsps.size() == 3;
        for(Rsp<Integer> rsp: rsps)
            assert rsp.getValue() > 0;
    }

    public void testInvocationOfProtectedMethod() throws Exception {
        RspList<Boolean> rsps=d1.callRemoteMethods(null, "protectedMethod", null, null, RequestOptions.SYNC());
        System.out.println("rsps:\n" + rsps);
        assert rsps.size() == 3;
        for(Rsp<Boolean> rsp: rsps)
            assert rsp.getValue();
    }


    protected interface ParentInterface {
        boolean          wasCalled();
        default int      bar() {return 22;}
    }

    protected interface MyInterface extends ParentInterface {
        int              getNumCalls();
        void             reset();
        boolean          foo();
        default int      bar2() {return 44;}
    }

    protected static class ServerObject implements MyInterface {
        boolean called;
        int     num_calls;

        public boolean wasCalled() {
            return called;
        }
        public int     getNumCalls() {return num_calls;}
        public void    reset() {called=false; num_calls=0;}

        public boolean foo() {
            num_calls++;
            return called=true;
        }

        protected static boolean protectedMethod() {return true;}
    }


    protected static class Subclass extends ServerObject {
        public static int foobar() {return 33;}
    }



}