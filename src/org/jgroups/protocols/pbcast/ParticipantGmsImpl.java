
package org.jgroups.protocols.pbcast;

import org.jgroups.Address;
import org.jgroups.View;
import org.jgroups.ViewId;
import org.jgroups.util.Digest;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;


/**
 * @author Bela Ban
 */
public class ParticipantGmsImpl extends ServerGmsImpl {
    private final Collection<Address> suspected_mbrs=new LinkedHashSet<>();


    public ParticipantGmsImpl(GMS g) {
        super(g);
    }


    public void init() throws Exception {
        super.init();
        suspected_mbrs.clear();
    }

    public void join(Address mbr, boolean useFlushIfPresent) {
        wrongMethod("join");
    }

    public void joinWithStateTransfer(Address mbr,boolean useFlushIfPresent) {
        wrongMethod("join");
    }


    public void leave(Address mbr) {
        if(sendLeaveReqToCoord(gms.determineCoordinator()))
            gms.initState();
    }


    /** In case we get a different JOIN_RSP from a previous JOIN_REQ sent by us (as a client), we simply apply the
     * new view if it is greater than ours
     */
    @Override
    public void handleJoinResponse(JoinRsp join_rsp) {
        View v=join_rsp.getView();
        ViewId tmp_vid=v != null? v.getViewId() : null;
        ViewId my_view=gms.getViewId();
        if(tmp_vid != null && my_view != null && tmp_vid.compareToIDs(my_view) > 0) {
            Digest d=join_rsp.getDigest();
            gms.installView(v, d);
        }
    }



    public void suspect(Address mbr) {
        Collection<Request> suspected=new LinkedHashSet<>(1);
        suspected.add(new Request(Request.SUSPECT, mbr));
        handleMembershipChange(suspected);
    }


    /** Removes previously suspected member from list of currently suspected members */
    public void unsuspect(Address mbr) {
        if(mbr != null)
            suspected_mbrs.remove(mbr);
    }


    public void handleMembershipChange(Collection<Request> requests) {
        Collection<Address> leaving_mbrs=new LinkedHashSet<>(requests.size());
        requests.forEach(r -> {
            if(r.type == Request.SUSPECT)
                suspected_mbrs.add(r.mbr);
            else if(r.type == Request.LEAVE)
                leaving_mbrs.add(r.mbr);
        });

        if(suspected_mbrs.isEmpty() && leaving_mbrs.isEmpty())
            return;

        if(wouldIBeCoordinator(leaving_mbrs)) {
            log.debug("%s: members are %s, coord=%s: I'm the new coordinator", gms.local_addr, gms.members, gms.local_addr);
            boolean leaving=gms.isLeaving();
            gms.becomeCoordinator();
            Collection<Request> leavingOrSuspectedMembers=new LinkedHashSet<>();
            leaving_mbrs.forEach(mbr -> leavingOrSuspectedMembers.add(new Request(Request.LEAVE, mbr)));
            suspected_mbrs.forEach(mbr -> {
                leavingOrSuspectedMembers.add(new Request(Request.SUSPECT, mbr));
                gms.ack_collector.suspect(mbr);
            });
            suspected_mbrs.clear();
            if(leaving)
                leavingOrSuspectedMembers.add(new Request(Request.COORD_LEAVE, gms.local_addr));
            gms.getViewHandler().add(leavingOrSuspectedMembers);
        }
    }


    public void handleViewChange(View view, Digest digest) {
        suspected_mbrs.clear();
        super.handleViewChange(view, digest);
    }

    @Override protected void coordChanged(Address from, Address to) {
        super.coordChanged(from, to);
        if(gms.isLeaving()) {
            log.trace("%s: resending LEAVE request to new coordinator %s (prev coord=%s)", gms.local_addr, to, from);
            sendLeaveMessage(to, gms.local_addr);
        }
    }

    /* ---------------------------------- Private Methods --------------------------------------- */

    /**
     * Determines whether this member is the new coordinator given a list of suspected members.  This is
     * computed as follows: the list of currently suspected members (suspected_mbrs) is removed from the current
     * membership. If the first member of the resulting list is equals to the local_addr, then it is true,
     * otherwise false. Example: own address is B, current membership is {A, B, C, D}, suspected members are {A,
     * D}. The resulting list is {B, C}. The first member of {B, C} is B, which is equal to the
     * local_addr. Therefore, true is returned.
     */
    boolean wouldIBeCoordinator(Collection<Address> leaving_mbrs) {
        List<Address> mbrs=gms.computeNewMembership(gms.members.getMembers(), null, leaving_mbrs, suspected_mbrs);
        if(mbrs.isEmpty()) return false;
        Address new_coord=mbrs.get(0);
        return gms.local_addr.equals(new_coord);
    }


    /* ------------------------------ End of Private Methods ------------------------------------ */

}
