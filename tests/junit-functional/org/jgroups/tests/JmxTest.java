package org.jgroups.tests;

import org.jgroups.Global;
import org.jgroups.annotations.ManagedAttribute;
import org.jgroups.annotations.ManagedOperation;
import org.jgroups.annotations.Property;
import org.jgroups.jmx.AdditionalJmxObjects;
import org.jgroups.jmx.JmxConfigurator;
import org.jgroups.util.Util;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import javax.management.*;
import java.util.Date;

/**
 * Tests exposing attributes and operations via JMX using annotations (@ManagedAttribute, @ManagedOperation)
 * @author Bela Ban
 * @since  3.3
 */
@Test(groups=Global.FUNCTIONAL,singleThreaded=true)
public class JmxTest {
    protected MBeanServer         server;
    protected static final String NAME="jmxtest:name=obj";
    protected ObjectName          obj_name;
    protected Child               obj;


    @BeforeClass
    protected void create() {server=Util.getMBeanServer();}

    @BeforeMethod
    protected void setup() throws Exception {
        obj_name=new ObjectName(NAME);
        obj=new Child();
        JmxConfigurator.register(obj, server, NAME);
        assert server.isRegistered(obj_name);
    }


    @AfterMethod
    protected void destroy() throws Exception {
        JmxConfigurator.unregister(server, NAME);
    }


    public void testAttrWithReadOnlyAccess() throws Exception {
        check("age", false);
        setAttribute("age", 23); // cannot set
        assert getAttribute("age").equals((short)22); // initial_value
        check("ssn",false, (long)322649, 10000);
    }

    public void testAttrWithReadWriteAccess() throws Exception {
        check("age2", true, (short)0, (short)22);
        check("timer.keep_alive_time", true, (long)5000, (long)2000);
    }

    public void testGetter() throws Exception {
        assert !attrExists("unknown");
        check("last_name",   true, "Fechner", "Ban");
        check("first_name",  true, "Jeannette", "Bela");
        check("flag",        true, true, false);
        check("another",     true, false, true);
    }

    /** Tests accessors that don't have a backing attribute (write is a no-op) */
    public void testAccessorsWithoutAttribute() throws Exception {
        check("synthesized_value",true);
        Object val=getAttribute("synthesized_value");
        assert val.equals(322649);
        setAttribute("synthesized_value", 10000);
        val=getAttribute("synthesized_value");
        assert val.equals(322649);
    }

    public void testJavaStyleAttributes() throws Exception {
        check("javaStyleFlag", true, true, false);
    }

    public void testAdditionalJmxObjects() throws Exception {
        ProvideAdditionalObjects objs=new ProvideAdditionalObjects();
        JmxConfigurator.register(objs, server, "jmxtest:name=additional_obj");
        ObjectName n=new ObjectName("jmxtest:name=additional_obj");

        Object val=getAttribute(n, "num_msgs_sent");
        assert (int)val == 0;
        val=getAttribute(n, "num_msgs_received");
        assert (int)val == 0;
    }


    protected void check(String attr_name, boolean writable) throws Exception {
        check(attr_name, writable, null, null);
    }

    protected void check(String attr_name, boolean writable, Object expected_value,
                         Object new_value) throws Exception {
        assert attrExists(attr_name);
        MBeanAttributeInfo info=getAttributeInfo(attr_name);
        System.out.println(attr_name + ": " + info);
        assert info.isWritable() == writable;
        Object val=getAttribute(attr_name);
        if(expected_value != null)
            assert val.equals(expected_value) : "value of \"" + attr_name + "\" is " + val + ", but expected " + expected_value;
        if(new_value == null || !isWritable(attr_name))
            return;
        setAttribute(attr_name, new_value);
        val=getAttribute(attr_name);
        assert val.equals(new_value) : "value of \"" + attr_name + "\" is " + val + ", but expected " + new_value;
    }


    protected boolean attrExists(String attr_name) {
        try {
            return server.getAttribute(obj_name, attr_name) != null;
        }
        catch(AttributeNotFoundException e) {
            return false;
        }
        catch(Throwable t) {
            throw new RuntimeException(t);
        }
    }

    protected boolean isWritable(String attr_name) {
        MBeanAttributeInfo attr_info=getAttributeInfo(attr_name);
        return attr_info != null && attr_info.isWritable();
    }

    protected MBeanAttributeInfo getAttributeInfo(String attr_name) {
        MBeanAttributeInfo[] attrs=new MBeanAttributeInfo[0];
        try {
            attrs=server.getMBeanInfo(obj_name).getAttributes();
            for(MBeanAttributeInfo info: attrs)
                if(info.getName().equals(attr_name))
                    return info;
            return null;
        }
        catch(Throwable t) {
            return null;
        }
    }

    protected void setAttribute(String attr_name, Object value) throws Exception {
        server.setAttribute(obj_name, new Attribute(attr_name, value));
    }

    protected Object getAttribute(String attr_name) throws Exception {
        return getAttribute(obj_name, attr_name);
    }

    protected Object getAttribute(ObjectName name, String attr_name) throws Exception {
        return server.getAttribute(name, attr_name);
    }

    protected static class Parent {
        @SuppressWarnings("FieldMayBeFinal")
        @ManagedAttribute(description="age",writable=false)
        private short age=22;   // exposed as read-only 'age'

        @ManagedAttribute(description="age2",writable=true)
        protected short age2;

        @Property(name="timer.keep_alive_time", description="Timeout")
        protected long keep_alive=5000;

        protected String last_name="Fechner";

        @ManagedAttribute(description="social security number",name="ssn") // read-only
        private static final long ssn=322649L;


        @ManagedAttribute(description="setter",writable=true)
        public String lastName()                {return last_name;}
        public Parent lastName(String new_name) {last_name=new_name; return this;}
    }


    protected static class Child extends Parent {
        protected String  first_name="Jeannette";
        protected boolean flag=true;

        @Property(name="Another",writable=true)
        protected boolean another_flag;

        @Property(writable=true)
        protected boolean javaStyleFlag=true;

        public String firstName()                   {return first_name;}

        @ManagedAttribute(description="setter",writable=true)
        public Parent firstName(String new_name)    {first_name=new_name; return this;}

        @ManagedAttribute(description="flag",writable=true)
        public boolean isFlag()                     {return flag;}

        public static int getSynthesizedValue()     {return 322649;}
        @ManagedAttribute(description="synthesized",writable=true)
        public Child      synthesizedValue(int v)   {return this;}

        @ManagedAttribute(name="another",writable=true)
        public boolean isAnotherFlag()              {return another_flag;}
        public Child   anotherFlag(boolean flag)    {another_flag=flag; return this;}

        public boolean javaStyleFlag()              {return javaStyleFlag;}
        public Child   javaStyleFlag(boolean flag)  {javaStyleFlag=flag; return this;}
    }

    protected static class ProvideAdditionalObjects implements AdditionalJmxObjects {
        protected final AdditionalInfo info=new AdditionalInfo();

        @ManagedAttribute(description="age",writable=true)
        protected int age=10;

        public Object[] getJmxObjects() {
            return new Object[]{info};
        }
    }

    protected static class AdditionalInfo {
        @ManagedAttribute(description="number of msgs sent")
        protected int num_msgs_sent;
        @ManagedAttribute(description="number of msgs sent", writable=true)
        public int num_msgs_received;

        @ManagedAttribute(description="current date")
        public static String getDate() {
            return new Date().toString();
        }

        @ManagedOperation(description="say name")
        public static void sayName() {
            System.out.printf("hello world\n");
        }

        @ManagedOperation(description="foo")
        public static void foo() {
            System.out.println("foo()");
        }
    }
}
