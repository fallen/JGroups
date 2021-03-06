package org.jgroups.protocols;

import org.jgroups.Address;
import org.jgroups.Event;
import org.jgroups.Message;
import org.jgroups.annotations.MBean;
import org.jgroups.annotations.ManagedAttribute;
import org.jgroups.annotations.ManagedOperation;
import org.jgroups.util.CreditMap;
import org.jgroups.util.Tuple;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.Vector;


/**
 * Simple flow control protocol based on a credit system. Each sender has a number of credits (bytes
 * to send). When the credits have been exhausted, the sender blocks. Each receiver also keeps track of
 * how many credits it has received from a sender. When credits for a sender fall below a threshold,
 * the receiver sends more credits to the sender. Works for both unicast and multicast messages.
 * <p/>
 * Note that this protocol must be located towards the top of the stack, or all down_threads from JChannel to this
 * protocol must be set to false ! This is in order to block JChannel.send()/JChannel.down().
 * <br/>This is the second simplified implementation of the same model. The algorithm is sketched out in
 * doc/FlowControl.txt
 * <br/>
 * Changes (Brian) April 2006:
 * <ol>
 * <li>Receivers now send credits to a sender when more than min_credits have been received (rather than when min_credits
 * are left)
 * <li>Receivers don't send the full credits (max_credits), but rather the actual number of bytes received
 * <ol/>
 * @author Bela Ban
 */
@MBean(description="Simple flow control protocol based on a credit system")
public class MFC extends FlowControl {

    
    
    /* --------------------------------------------- Fields ------------------------------------------------------ */
    

    /** Maintains credits per member */
    protected CreditMap credits;

    
    /** Last time a credit request was sent. Used to prevent credit request storms */
    protected long last_credit_request=0;

   

    /** Allows to unblock a blocked sender from an external program, e.g. JMX */
    @ManagedOperation(description="Unblock a sender")
    public void unblock() {
        if(log.isTraceEnabled())
            log.trace("unblocking the sender and replenishing all members");
        credits.replenishAll();
    }

    @ManagedOperation(description="Print credits")
    public String printCredits() {
        return super.printCredits() + "\nsenders min credits: " + credits.getMinCredits();
    }

    @ManagedOperation(description="Print sender credits")
    public String printSenderCredits() {
        return credits.toString();
    }

    @ManagedAttribute(description="Number of times flow control blocks sender")
    public int getNumberOfBlockings() {
        return credits.getNumBlockings();
    }

    @ManagedAttribute(description="Total time (ms) spent in flow control block")
    public long getTotalTimeBlocked() {
        return credits.getTotalBlockTime();
    }

    protected boolean handleMulticastMessage() {
        return true;
    }

   
    public void init() throws Exception {
        super.init();
        credits=new CreditMap(max_credits);
    }

    public void stop() {
        super.stop();
        credits.clear();
    }

    protected Object handleDownMessage(final Event evt, final Message msg, Address dest, int length) {
        if(dest != null && !dest.isMulticastAddress()) { // 2nd line of defense, not really needed
            log.error(getClass().getSimpleName() + " doesn't handle unicast messages; passing message down");
            return down_prot.down(evt);
        }

        long block_time=max_block_times != null? getMaxBlockTime(length) : max_block_time;
        while(running) {
            boolean rc=credits.decrement(length, block_time);
            if(rc || max_block_times != null || !running)
                break;

            if(needToSendCreditRequest()) {
                List<Tuple<Address,Long>> targets=credits.getMembersWithCreditsLessThan(min_credits);
                for(Tuple<Address,Long> tuple: targets)
                    sendCreditRequest(tuple.getVal1(), Math.min(max_credits, max_credits - tuple.getVal2()));
            }
        }
        
        // send message - either after regular processing, or after blocking (when enough credits are available again)
        return down_prot.down(evt);
    }




    protected synchronized boolean needToSendCreditRequest() {
        long curr_time=System.currentTimeMillis();
        long wait_time=curr_time - last_credit_request;
        if(wait_time >= max_block_time) {
            last_credit_request=curr_time;
            return true;
        }
        return false;
    }
  


    protected void handleCredit(Address sender, long increase) {
        credits.replenish(sender, increase);
        if(log.isTraceEnabled()) {
            StringBuilder sb=new StringBuilder();
            sb.append("received " + increase + " credits from ").append(sender).append(", new credits for " + sender + " : ")
                    .append(credits.get(sender) + ", min_credits=" + credits.getMinCredits());
            log.trace(sb);
        }
    }


    protected void handleViewChange(Vector<Address> mbrs) {
        super.handleViewChange(mbrs);

        Set<Address> keys=new HashSet<Address>(credits.keys());
        for(Address key: keys) {
            if(!mbrs.contains(key))
                credits.remove(key);
        }

        for(Address key: mbrs)
            credits.putIfAbsent(key);
    }




}
