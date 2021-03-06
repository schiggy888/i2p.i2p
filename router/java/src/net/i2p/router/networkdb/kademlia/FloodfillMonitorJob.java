package net.i2p.router.networkdb.kademlia;

import java.util.List;

import net.i2p.data.Hash;
import net.i2p.data.RouterAddress;
import net.i2p.data.RouterInfo;
import net.i2p.router.JobImpl;
import net.i2p.router.Router;
import net.i2p.router.RouterContext;
import net.i2p.router.peermanager.PeerProfile;
import net.i2p.util.Log;

/**
 * Simple job to monitor the floodfill pool.
 * If we are class N or O, and meet some other criteria,
 * we will automatically become floodfill if there aren't enough.
 * But only change ff status every few hours to minimize ff churn.
 *
 */
class FloodfillMonitorJob extends JobImpl {
    private final Log _log;
    private final FloodfillNetworkDatabaseFacade _facade;
    private long _lastChanged;
    
    private static final int REQUEUE_DELAY = 60*60*1000;
    private static final long MIN_UPTIME = 2*60*60*1000;
    private static final long MIN_CHANGE_DELAY = 6*60*60*1000;
    private static final int MIN_FF = 5000;
    private static final int MAX_FF = 999999;
    private static final String PROP_FLOODFILL_PARTICIPANT = "router.floodfillParticipant";
    
    public FloodfillMonitorJob(RouterContext context, FloodfillNetworkDatabaseFacade facade) {
        super(context);
        _facade = facade;
        _log = context.logManager().getLog(FloodfillMonitorJob.class);
    }
    
    public String getName() { return "Monitor the floodfill pool"; }

    public void runJob() {
        boolean wasFF = _facade.floodfillEnabled();
        boolean ff = shouldBeFloodfill();
        _facade.setFloodfillEnabled(ff);
        if (ff != wasFF)
            getContext().router().rebuildRouterInfo();
        if (_log.shouldLog(Log.INFO))
            _log.info("Should we be floodfill? " + ff);
        int delay = (REQUEUE_DELAY / 2) + getContext().random().nextInt(REQUEUE_DELAY);
        // there's a lot of eligible non-floodfills, keep them from all jumping in at once
        // To do: somehow assess the size of the network to make this adaptive?
        if (!ff)
            delay *= 4; // this was 7, reduced for moar FFs --zab
        requeue(delay);
    }

    private boolean shouldBeFloodfill() {
        // Only if not shutting down...
        if (getContext().getProperty(Router.PROP_SHUTDOWN_IN_PROGRESS) != null)
            return false;

        // Hidden trumps netDb.floodfillParticipant=true
        if (getContext().router().isHidden())
            return false;

        String enabled = getContext().getProperty(PROP_FLOODFILL_PARTICIPANT, "auto");
        if ("true".equals(enabled))
            return true;
        if ("false".equals(enabled))
            return false;

        // auto from here down

        // Only if up a while...
        if (getContext().router().getUptime() < MIN_UPTIME)
            return false;

        RouterInfo ri = getContext().router().getRouterInfo();
        if (ri == null)
            return false;
        char bw = ri.getBandwidthTier().charAt(0);
        // Only if class N or O...
        if (bw < Router.CAPABILITY_BW128 || bw > Router.CAPABILITY_BW256)
            return false;

        // This list will not include ourselves...
        List<Hash> floodfillPeers = _facade.getFloodfillPeers();
        long now = getContext().clock().now();
        // We know none at all! Must be our turn...
        if (floodfillPeers == null || floodfillPeers.isEmpty()) {
            _lastChanged = now;
            return true;
        }

        // Only change status every so often
        boolean wasFF = _facade.floodfillEnabled();
        if (_lastChanged + MIN_CHANGE_DELAY > now)
            return wasFF;

        // This is similar to the qualification we do in FloodOnlySearchJob.runJob().
        // Count the "good" ff peers.
        //
        // Who's not good?
        // the unheard-from, unprofiled, failing, unreachable and banlisted ones.
        // We should hear from floodfills pretty frequently so set a 60m time limit.
        // If unprofiled we haven't talked to them in a long time.
        // We aren't contacting the peer directly, so banlist doesn't strictly matter,
        // but it's a bad sign, and we often banlist a peer before we fail it...
        //
        // Future: use Integration calculation
        //
        int ffcount = floodfillPeers.size();
        int failcount = 0;
        long before = now - 60*60*1000;
        for (Hash peer : floodfillPeers) {
            PeerProfile profile = getContext().profileOrganizer().getProfile(peer);
            if (profile == null || profile.getLastHeardFrom() < before ||
                profile.getIsFailing() || getContext().banlist().isBanlisted(peer) ||
                getContext().commSystem().wasUnreachable(peer))
                failcount++;
        }

        if (wasFF)
            ffcount++;
        int good = ffcount - failcount;
        boolean happy = getContext().router().getRouterInfo().getCapabilities().indexOf("R") >= 0;
        // Use the same job lag test as in RouterThrottleImpl
        happy = happy && getContext().jobQueue().getMaxLag() < 2*1000;
        // Only if we're pretty well integrated...
        happy = happy && _facade.getKnownRouters() >= 200;
        happy = happy && getContext().commSystem().countActivePeers() >= 50;
        happy = happy && getContext().tunnelManager().getParticipatingCount() >= 50;
        happy = happy && Math.abs(getContext().clock().getOffset()) < 10*1000;
        // We need an address and no introducers
        if (happy) {
            RouterAddress ra = getContext().router().getRouterInfo().getTargetAddress("SSU");
            if (ra == null)
                happy = false;
            else {
                if (ra.getOption("ihost0") != null)
                   happy = false;
            }
        }
        
        if (_log.shouldLog(Log.DEBUG)) {
            final RouterContext rc = getContext();
            final String log = String.format(
                    "FF criteria breakdown: happy=%b, capabilities=%s, maxLag=%d, known=%d, " +
                    "active=%d, participating=%d, offset=%d, ssuAddr=%b",
                    happy, 
                    rc.router().getRouterInfo().getCapabilities(),
                    rc.jobQueue().getMaxLag(),
                    _facade.getKnownRouters(),
                    rc.commSystem().countActivePeers(),
                    rc.tunnelManager().getParticipatingCount(),
                    Math.abs(rc.clock().getOffset()),
                    rc.router().getRouterInfo().getTargetAddress("SSU")
                    );
            _log.debug(log);
        }


        // Too few, and we're reachable, let's volunteer
        if (good < MIN_FF && happy) {
            if (!wasFF) {
                _lastChanged = now;
                _log.logAlways(Log.INFO, "Only " + good + " ff peers and we want " + MIN_FF + " so we are becoming floodfill");
            }
            return true;
        }

        // Too many, or we aren't reachable, let's stop
        if (good > MAX_FF || (good > MIN_FF && !happy)) {
            if (wasFF) {
                _lastChanged = now;
                _log.logAlways(Log.INFO, "Have " + good + " ff peers and we need only " + MIN_FF + " to " + MAX_FF +
                               " so we are disabling floodfill; reachable? " + happy);
            }
            return false;
        }

        if (_log.shouldLog(Log.INFO))
            _log.info("Have " + good + " ff peers, not changing, enabled? " + wasFF + "; reachable? " + happy);
        return wasFF;
    }
    
}
