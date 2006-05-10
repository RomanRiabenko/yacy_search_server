// yacyCore.java
// -------------------------------------
// (C) by Michael Peter Christen; mc@anomic.de
// first published on http://www.anomic.de
// Frankfurt, Germany, 2004
//
// $LastChangedDate$
// $LastChangedRevision$
// $LastChangedBy$
//
// This program is free software; you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation; either version 2 of the License, or
// (at your option) any later version.
//
// This program is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.
//
// You should have received a copy of the GNU General Public License
// along with this program; if not, write to the Free Software
// Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
//
// Using this software in any meaning (reading, learning, copying, compiling,
// running) means that you agree that the Author(s) is (are) not responsible
// for cost, loss of data or any harm that may be caused directly or indirectly
// by usage of this softare or this documentation. The usage of this software
// is on your own risk. The installation and usage (starting/running) of this
// software may allow other people or application to access your computer and
// any attached devices and is highly dependent on the configuration of the
// software which must be done by the user of the software; the author(s) is
// (are) also not responsible for proper configuration and usage of the
// software, even if provoked by documentation provided together with
// the software.
//
// Any changes to this file according to the GPL as documented in the file
// gpl.txt aside this file in the shipment you received can be done to the
// lines that follows this copyright notice here, but changes must not be
// done inside the copyright notive above. A re-distribution must contain
// the intact and unchanged copyright notice.
// Contributions and changes to the program code must be marked as such.

/*
  the yacy process of getting in touch of other peers starts as follows:
  - init seed cache. It is needed to determine the right peer for the Hello-Process
  - create a own seed. This can be a new one or one loaded from a file
  - The httpd must start up then first
  - the own seed is completed by performing the 'yacyHello' process. This
    process will result in a request back to the own peer to check if it runs
    in server mode. This is the reason that the httpd must be started in advance.

*/

// contributions:
// principal peer status via file generation by Alexander Schier [AS]

package de.anomic.yacy;

import java.io.File;
import java.io.FilenameFilter;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.GregorianCalendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import de.anomic.http.httpc;
import de.anomic.net.natLib;
import de.anomic.plasma.plasmaSwitchboard;
import de.anomic.server.serverCore;
import de.anomic.server.serverSemaphore;
import de.anomic.server.serverSwitch;
import de.anomic.server.serverDate;
import de.anomic.server.logging.serverLog;
import de.anomic.yacy.yacySeed;

public class yacyCore {

    // statics
    public static ThreadGroup publishThreadGroup = new ThreadGroup("publishThreadGroup");
    public static long startupTime = System.currentTimeMillis();
    public static yacySeedDB seedDB = null;
    public static yacyNewsPool newsPool = null;
    public static final HashMap seedUploadMethods = new HashMap();
    public static yacyPeerActions peerActions = null;
    public static yacyDHTAction dhtAgent = null;
    public static serverLog log;
    public static long lastOnlineTime = 0;
    public static float latestVersion = (float) 0.1;
    public static long speedKey = 0;
    public static File yacyDBPath;
    public static final Map amIAccessibleDB = Collections.synchronizedMap(new HashMap()); // Holds PeerHash / yacyAccessible Relations
    // constants for PeerPing behaviour
    private static final int peerPingInitial = 10;
    private static final int peerPingMaxRunning = 3;
    private static final int peerPingMinRunning = 1;
    private static final int peerPingMinDBSize = 5;
    private static final int peerPingMinAccessibleToForceSenior = 3;
    private static final long peerPingMaxDBAge = 15 * 60 * 1000; // in milliseconds

    // public static yacyShare shareManager = null;
    // public static boolean terminate = false;

    // class variables
    private int lastSeedUpload_seedDBSize = 0;
    public long lastSeedUpload_timeStamp = System.currentTimeMillis();
    //private String lastSeedUpload_myPeerType = "";
    private String lastSeedUpload_myIP = "";

    private static int onlineMode = 1;
    private plasmaSwitchboard switchboard;

    private static TimeZone GMTTimeZone = TimeZone.getTimeZone("America/Los_Angeles");
    public static String universalDateShortPattern = "yyyyMMddHHmmss";
    public static SimpleDateFormat shortFormatter = new SimpleDateFormat(universalDateShortPattern);

    public static long universalTime() {
        return universalDate().getTime();
    }

    public static Date universalDate() {
        return new GregorianCalendar(GMTTimeZone).getTime();
    }

    public static String universalDateShortString() {
        return universalDateShortString(universalDate());
    }

    public static String universalDateShortString(Date date) {
        return shortFormatter.format(date);
    }

    public static Date parseUniversalDate(String remoteTimeString, String remoteUTCOffset) {
        if (remoteTimeString == null || remoteTimeString.length() == 0) { return new Date(); }
        if (remoteUTCOffset == null || remoteUTCOffset.length() == 0) { return new Date(); }
        try {
            return new Date(yacyCore.shortFormatter.parse(remoteTimeString).getTime() - serverDate.UTCDiff() + serverDate.UTCDiff(remoteUTCOffset));
        } catch (java.text.ParseException e) {
            log.logFinest("parseUniversalDate " + e.getMessage() + ", remoteTimeString=[" + remoteTimeString + "]");
            return new Date();
        } catch (java.lang.NumberFormatException e) {
            log.logFinest("parseUniversalDate " + e.getMessage() + ", remoteTimeString=[" + remoteTimeString + "]");
            return new Date();
        }
    }

    public static int yacyTime() {
        // the time since startup of yacy in seconds
        return (int) ((System.currentTimeMillis() - startupTime) / 1000);
    }

    public yacyCore(plasmaSwitchboard sb) {
        long time = System.currentTimeMillis();

        this.switchboard = sb;
        switchboard.setConfig("yacyStatus", "");

        // set log level
        log = new serverLog("YACY");

        // create a yacy db
        yacyDBPath = new File(sb.getRootPath(), sb.getConfig("yacyDB", "DATA/YACYDB"));
        if (!yacyDBPath.exists()) { yacyDBPath.mkdir(); }

        // create or init seed cache
        int memDHT = Integer.parseInt(switchboard.getConfig("ramCacheDHT", "1024")) / 1024;
        log.logConfig("DHT Cache memory = " + memDHT + " KB");
        seedDB = new yacySeedDB(
                sb,
                new File(yacyDBPath, "seed.new.db"),
                new File(yacyDBPath, "seed.old.db"),
                new File(yacyDBPath, "seed.pot.db"),
                memDHT);

        // create or init news database
        int memNews = Integer.parseInt(switchboard.getConfig("ramCacheNews", "1024")) / 1024;
        log.logConfig("News Cache memory = " + memNews + " KB");
        newsPool = new yacyNewsPool(yacyDBPath, memNews);

        loadSeedUploadMethods();

        // deploy peer actions
        peerActions = new yacyPeerActions(seedDB, switchboard,
                new File(sb.getRootPath(), sb.getConfig("superseedFile", "superseed.txt")),
                switchboard.getConfig("superseedLocation", "http://www.yacy.net/yacy/superseed.txt"));
        dhtAgent = new yacyDHTAction(seedDB);
        peerActions.deploy(dhtAgent);
        peerActions.deploy(new yacyNewsAction(newsPool));

        // create or init index sharing
//      shareManager = new yacyShare(switchboard);

        lastSeedUpload_seedDBSize = seedDB.sizeConnected();

        log.logConfig("CORE INITIALIZED");
        // ATTENTION, VERY IMPORTANT: before starting the thread, the httpd yacy server must be running!

        speedKey = System.currentTimeMillis() - time;

        // start with a seedList update to propagate out peer, if possible
        onlineMode = Integer.parseInt(switchboard.getConfig("onlineMode", "1"));
        //lastSeedUpdate = universalTime();
        lastOnlineTime = 0;

        // cycle
        // within cycle: update seed file, strengthen network, pass news (new, old seed's)
        if (online()) {
            log.logConfig("you are in online mode");
        } else {
            log.logConfig("YOU ARE OFFLINE! ---");
            log.logConfig("--- TO START BOOTSTRAPING, YOU MUST USE THE PROXY,");
            log.logConfig("--- OR HIT THE BUTTON 'go online'");
            log.logConfig("--- ON THE STATUS PAGE http://localhost:" + serverCore.getPortNr(switchboard.getConfig("port", "8080")) + "/Status.html");
        }
    }

    synchronized static public void triggerOnlineAction() {
        lastOnlineTime = System.currentTimeMillis();
    }

    public boolean online() {
        onlineMode = Integer.parseInt(switchboard.getConfig("onlineMode", "1"));
    return ((onlineMode == 2) || ((System.currentTimeMillis() - lastOnlineTime) < 10000));
    }

    public static int getOnlineMode() {
        return onlineMode;
    }
    
    public static void setOnlineMode(int newOnlineMode) {
    	onlineMode = newOnlineMode;
    	return;
    }
    
    public void loadSeeds() {
        //new Thread(new vprobe()).start();
        peerActions.loadSeedLists(); // start to bootstrap the network here
        publishSeedList();
    }

    public void publishSeedList() {
        log.logFine("yacyCore.publishSeedList: Triggered Seed Publish");

        /*
        if (oldIPStamp.equals((String) seedDB.mySeed.get(yacySeed.IP, "127.0.0.1")))
            yacyCore.log.logDebug("***DEBUG publishSeedList: oldIP is equal");
        if (seedCacheSizeStamp == seedDB.sizeConnected())
            yacyCore.log.logDebug("***DEBUG publishSeedList: sizeConnected is equal");
        if (canReachMyself())
            yacyCore.log.logDebug("***DEBUG publishSeedList: I can reach myself");
        */

        if (
                (this.lastSeedUpload_myIP.equals(seedDB.mySeed.get(yacySeed.IP, "127.0.0.1"))) &&
                (this.lastSeedUpload_seedDBSize == seedDB.sizeConnected()) &&
                (canReachMyself()) &&
                (System.currentTimeMillis() - this.lastSeedUpload_timeStamp < 1000*60*60*24) &&
                (seedDB.mySeed.isPrincipal())
        ) {
            log.logFine("yacyCore.publishSeedList: not necessary to publish: oldIP is equal, sizeConnected is equal and I can reach myself under the old IP.");
            return;
        }

        // getting the seed upload method that should be used ...
        final String seedUploadMethod = this.switchboard.getConfig("seedUploadMethod", "");

        if (
                (!seedUploadMethod.equalsIgnoreCase("none")) ||
                ((seedUploadMethod.equals("")) && (this.switchboard.getConfig("seedFTPPassword", "").length() > 0)) ||
                ((seedUploadMethod.equals("")) && (this.switchboard.getConfig("seedFilePath", "").length() > 0))
        ) {
            if (seedUploadMethod.equals("")) {
                if (this.switchboard.getConfig("seedFTPPassword", "").length() > 0)
                    this.switchboard.setConfig("seedUploadMethod","Ftp");
                if (this.switchboard.getConfig("seedFilePath", "").length() > 0)
                    this.switchboard.setConfig("seedUploadMethod","File");
            }
            // we want to be a principal...
            saveSeedList();
        } else {
            if (seedUploadMethod.equals("")) this.switchboard.setConfig("seedUploadMethod","none");
            log.logFine("yacyCore.publishSeedList: No uploading method configured");
            return;
        }
    }

    public void peerPing() {
        if (!online()) return;

        // before publishing, update some seed data
        peerActions.updateMySeed();

        // publish own seed to other peer, this can every peer, but makes only sense for senior peers
        final int oldSize = seedDB.sizeConnected();
        if (oldSize == 0) {
            // reload the seed lists
            peerActions.loadSeedLists();
            log.logInfo("re-initialized seed list. received " + seedDB.sizeConnected() + " new peer(s)");
        }
        final int newSeeds = publishMySeed(false);
        if (newSeeds > 0) log.logInfo("received " + newSeeds + " new peer(s), know a total of " +
                                      seedDB.sizeConnected() + " different peers");
    }

    private boolean canReachMyself() {
        // returns true if we can reach ourself under our known peer address
        // if we cannot reach ourself, we call a forced publishMySeed and return false
        final int urlc = yacyClient.queryUrlCount(seedDB.mySeed);
        if (urlc >= 0) {
            seedDB.mySeed.put(yacySeed.LASTSEEN, universalDateShortString(new Date()));
            return true;
        }
        log.logInfo("re-connect own seed");
        final String oldAddress = seedDB.mySeed.getAddress();
        /*final int newSeeds =*/ publishMySeed(true);
        return (oldAddress != null && oldAddress.equals(seedDB.mySeed.getAddress()));
    }

    protected class publishThread extends Thread {
        public int added;
        public yacySeed seed;
        public Exception error;
        private final serverSemaphore sync;
        private final List syncList;

        public publishThread(ThreadGroup tg, yacySeed seed, serverSemaphore sync, List syncList) throws InterruptedException {
            super(tg, "PublishSeed_" + seed.getName());

            this.sync = sync;
            this.sync.P();
            this.syncList = syncList;

            this.seed = seed;
            this.added = 0;
            this.error = null;
        }

        public void run() {
            try {
                this.added = yacyClient.publishMySeed(seed.getAddress(), seed.hash);
                if (this.added < 0) {
                    // no or wrong response, delete that address
                    log.logInfo("publish: disconnected " + this.seed.get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_SENIOR) + " peer '" + this.seed.getName() + "' from " + this.seed.getAddress());
                    peerActions.peerDeparture(this.seed);
                } else {
                    // success! we have published our peer to a senior peer
                    // update latest news from the other peer
                    log.logInfo("publish: handshaked " + this.seed.get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_SENIOR) + " peer '" + this.seed.getName() + "' at " + this.seed.getAddress());
                }
            } catch (Exception e) {
                log.logSevere("publishThread: error with target seed " + seed.toString() + ": " + e.getMessage(), e);
                this.error = e;
            } finally {
                this.syncList.add(this);
                this.sync.V();
            }
        }
    }

    private int publishMySeed(boolean force) {
        try {
            // call this after the httpd was started up

            // we need to find out our own ip
            // This is not always easy, since the application may
            // live behind a firewall or nat.
            // the normal way to do this is either measure the value that java gives us,
            // but this is not correct if the peer lives behind a NAT/Router or has several
            // addresses and not the right one can be found out.
            // We have several alternatives:
            // 1. ask another peer. This should be normal and the default method.
            //    but if no other peer lives, or we don't know them, we cannot do that
            // 2. ask own NAT. This is only an option if the NAT is a DI604, because this is the
            //    only supported for address retrieval
            // 3. ask ip respond services in the internet. There are several, and they are all
            //    probed until we get a valid response.

            // init yacyHello-process
            yacySeed[] seeds;

            int attempts = seedDB.sizeConnected();

            // getting a list of peers to contact
            if (seedDB.mySeed.get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_VIRGIN).equals(yacySeed.PEERTYPE_VIRGIN)) {
                if (attempts > peerPingInitial) { attempts = peerPingInitial; }
                seeds = seedDB.seedsByAge(true, attempts + 10); // best for fast connection
            } else {
                int diff = peerPingMinDBSize - amIAccessibleDB.size();
                if (diff > peerPingMinRunning) {
                    diff = Math.min(diff, peerPingMaxRunning);
                    if (attempts > diff) { attempts = diff; }
                } else {
                    if (attempts > peerPingMinRunning) { attempts = peerPingMinRunning; }
                }
                seeds = seedDB.seedsByAge(false, attempts + 10); // best for seed list maintenance/cleaning
            }

            if (seeds == null) { return 0; }

            // This will try to get Peers that are not currently in amIAccessibleDB
            LinkedList seedList = new LinkedList();
            LinkedList tmpSeedList = new LinkedList();
            for(int i = 0; i < seeds.length; i++) {
                if (seeds[i] != null) {
                if (amIAccessibleDB.containsKey(seeds[i].hash)) {
                    tmpSeedList.add(seeds[i]);
                } else {
                    seedList.add(seeds[i]);
                }
                }
            }
            while (!tmpSeedList.isEmpty()) { seedList.add(tmpSeedList.remove(0)); }
            if (seedList.size() < attempts) { attempts = seedList.size(); }

            // include a YaCyNews record to my seed
            try {
                final yacyNewsRecord record = newsPool.myPublication();
                if (record == null) {
                    seedDB.mySeed.put("news", "");
                } else {
                    seedDB.mySeed.put("news", de.anomic.tools.crypt.simpleEncode(record.toString()));
                }
            } catch (IOException e) {
                log.logSevere("publishMySeed: problem with news encoding", e);
            }
            seedDB.mySeed.setUnusedFlags();
            
            // include current citation-rank file count
            seedDB.mySeed.put(yacySeed.CRWCNT, Integer.toString(switchboard.rankingOwnDistribution.size()));
            seedDB.mySeed.put(yacySeed.CRTCNT, Integer.toString(switchboard.rankingOtherDistribution.size()));
            int newSeeds = -1;
            //if (seeds.length > 1) {
            // holding a reference to all started threads
            int contactedSeedCount = 0;
            final List syncList = Collections.synchronizedList(new LinkedList()); // memory for threads
            final serverSemaphore sync = new serverSemaphore(attempts);

            // going through the peer list and starting a new publisher thread for each peer
            for (int i = 0; i < attempts; i++) {
                yacySeed seed = (yacySeed) seedList.remove(0);
                if (seed == null) continue;

                final String address = seed.getAddress();
                log.logFine("HELLO #" + i + " to peer '" + seed.get(yacySeed.NAME, "") + "' at " + address); // debug
                if ((address == null) || (seed.isProper() != null)) {
                    // we don't like that address, delete it
                    peerActions.peerDeparture(seed);
                    sync.P();
                } else {
                    // starting a new publisher thread
                    contactedSeedCount++;
                    (new publishThread(yacyCore.publishThreadGroup,seed,sync,syncList)).start();
                }
            }

            // receiving the result of all started publisher threads
            for (int j = 0; j < contactedSeedCount; j++) {

                // waiting for the next thread to finish
                sync.P();

                // if this is true something is wrong ...
                if (syncList.isEmpty()) {
                    log.logWarning("PeerPing: syncList.isEmpty()==true");
                    continue;
                    //return 0;
                }

                // getting a reference to the finished thread
                final publishThread t = (publishThread) syncList.remove(0);

                // getting the amount of new reported seeds
                if (t.added >= 0) {
                    if (newSeeds==-1) {
                        newSeeds =  t.added;
                    } else {
                        newSeeds += t.added;
                    }
                }
            }

            // Nobody contacted yet, try again until peerPingInitial attempts are through
            while ((newSeeds < 0) && (contactedSeedCount < peerPingInitial) && (!seedList.isEmpty())) {
                yacySeed seed = (yacySeed) seedList.remove(0);
                if (seed != null) {
                    final String address = seed.getAddress();
                    log.logFine("HELLO x" + contactedSeedCount + " to peer '" + seed.get(yacySeed.NAME, "") + "' at " + address); // debug
                    if ((address == null) || (seed.isProper() != null)) {
                        peerActions.peerDeparture(seed);
                    } else {
                        contactedSeedCount++;
                        //new publishThread(yacyCore.publishThreadGroup,seeds[i],sync,syncList)).start();
                        try {
                            newSeeds = yacyClient.publishMySeed(seed.getAddress(), seed.hash);
                            if (newSeeds < 0) {
                                log.logInfo("publish: disconnected " + seed.get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_SENIOR) + " peer '" + seed.getName() + "' from " + seed.getAddress());
                                peerActions.peerDeparture(seed);
                            } else {
                                log.logInfo("publish: handshaked " + seed.get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_SENIOR) + " peer '" + seed.getName() + "' at " + seed.getAddress());
                            }
                        } catch (Exception e) {
                            log.logSevere("publishMySeed: error with target seed " + seed.toString() + ": " + e.getMessage(), e);
                        }
                    }
                }
            }

            int accessible = 0;
            int notaccessible = 0;
            final long cutofftime = System.currentTimeMillis() - peerPingMaxDBAge;
            final int dbSize;
            synchronized(amIAccessibleDB) {
                dbSize = amIAccessibleDB.size();
                Iterator ai = amIAccessibleDB.keySet().iterator();
                while (ai.hasNext()) {
                    yacyAccessible ya = (yacyAccessible) amIAccessibleDB.get(ai.next());
                    if (ya.lastUpdated < cutofftime) {
                        ai.remove(); 
                    } else {
                        if (ya.IWasAccessed) { accessible++; }
                        else { notaccessible++; }
                    }
                }
            }
            log.logFine("DBSize before -> after Cleanup: " + dbSize + " -> " + amIAccessibleDB.size());
            log.logInfo("PeerPing: I am accessible for " + accessible +
                " peer(s), not accessible for " + notaccessible + " peer(s).");

            if ((accessible + notaccessible) > 0) {
                final String newPeerType;
                // At least one other Peer told us our type
                if ((accessible >= peerPingMinAccessibleToForceSenior) ||
                    (accessible >= notaccessible)) {
                    // We can be reached from a majority of other Peers
                    if (yacyCore.seedDB.mySeed.isPrincipal()) {
                        newPeerType = yacySeed.PEERTYPE_PRINCIPAL;
                    } else {
                        newPeerType = yacySeed.PEERTYPE_SENIOR;
                    }
                } else {
                    // We cannot be reached from the outside
                    newPeerType = yacySeed.PEERTYPE_JUNIOR;
                }
                if (yacyCore.seedDB.mySeed.orVirgin().equals(newPeerType)) { 
                    log.logInfo("PeerPing: myType is " + yacyCore.seedDB.mySeed.orVirgin());
                } else {
                    log.logInfo("PeerPing: changing myType from '" + yacyCore.seedDB.mySeed.orVirgin() + "' to '" + newPeerType + "'");
                    yacyCore.seedDB.mySeed.put(yacySeed.PEERTYPE, newPeerType);
                }
            } else {
                log.logInfo("PeerPing: No data, staying at myType: " + yacyCore.seedDB.mySeed.orVirgin());
            }

            if (newSeeds >= 0) {
                // success! we have published our peer to a senior peer
                // update latest news from the other peer
//              log.logInfo("publish: handshaked " + t.seed.get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_SENIOR) + " peer '" + t.seed.getName() + "' at " + t.seed.getAddress());
                peerActions.saveMySeed();
                return newSeeds;
            }

//            // wait
//            try {
//                if (i == 0) Thread.currentThread().sleep(2000); // after the first time wait some seconds
//                Thread.currentThread().sleep(1000 + 500 * v.size()); // wait a while
//            } catch (InterruptedException e) {}
//
//            // check all threads
//            for (int j = 0; j < v.size(); j++) {
//                t = (publishThread) v.elementAt(j);
//                added = t.added;
//                if (!(t.isAlive())) {
//                    //log.logDebug("PEER " + seeds[j].get(yacySeed.NAME, "") + " request terminated"); // debug
//                    if (added >= 0) {
//                        // success! we have published our peer to a senior peer
//                        // update latest news from the other peer
//                        //log.logInfo("publish: handshaked " + t.seed.get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_SENIOR) + " peer '" + t.seed.getName() + "' at " + t.seed.getAddress());
//                        peerActions.saveMySeed();
//                        return added;
//                    }
//                }
//            }

            // if we have an address, we do nothing
            if (seedDB.mySeed.isProper() == null && !force) { return 0; }

            // still no success: ask own NAT or internet responder
            final boolean DI604use = switchboard.getConfig("DI604use", "false").equals("true");
            final String  DI604pw  = switchboard.getConfig("DI604pw", "");
            String  ip       = switchboard.getConfig("staticIP", "");
            if (ip.equals("")) {
                ip = natLib.retrieveIP(DI604use, DI604pw);
            }
//          yacyCore.log.logDebug("DEBUG: new IP=" + ip);
            seedDB.mySeed.put(yacySeed.IP, ip);
            if (seedDB.mySeed.get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_JUNIOR).equals(yacySeed.PEERTYPE_JUNIOR)) // ???????????????
                seedDB.mySeed.put(yacySeed.PEERTYPE, yacySeed.PEERTYPE_SENIOR); // to start bootstraping, we need to be recognised as PEERTYPE_SENIOR peer
            log.logInfo("publish: no recipient found, asked NAT or responder; our address is " +
                    ((seedDB.mySeed.getAddress() == null) ? "unknown" : seedDB.mySeed.getAddress()));
            peerActions.saveMySeed();
            return 0;
        } catch (InterruptedException e) {
            try {
                log.logInfo("publish: Interruption detected while publishing my seed.");

                // consuming the theads interrupted signal
                Thread.interrupted();

                // interrupt all already started publishThreads
                log.logInfo("publish: Signaling shutdown to " + yacyCore.publishThreadGroup.activeCount() +  " remaining publishing threads ...");
                yacyCore.publishThreadGroup.interrupt();

                // waiting some time for the publishThreads to finish execution
                try { Thread.sleep(500); } catch (InterruptedException ex) {}

                // getting the amount of remaining publishing threads
                int threadCount  = yacyCore.publishThreadGroup.activeCount();
                final Thread[] threadList = new Thread[threadCount];
                threadCount = yacyCore.publishThreadGroup.enumerate(threadList);

                // we need to use a timeout here because of missing interruptable session threads ...
                log.logFine("publish: Trying to abort " + yacyCore.publishThreadGroup.activeCount() +  " remaining publishing threads ...");
                for ( int currentThreadIdx = 0; currentThreadIdx < threadCount; currentThreadIdx++ )  {
                    Thread currentThread = threadList[currentThreadIdx];

                    if (currentThread.isAlive()) {
                        log.logFine("publish: Closing socket of publishing thread '" + currentThread.getName() + "' [" + currentThreadIdx + "].");
                        httpc.closeOpenSockets(currentThread);
                    }
                }

                // we need to use a timeout here because of missing interruptable session threads ...
                log.logFine("publish: Waiting for " + yacyCore.publishThreadGroup.activeCount() +  " remaining publishing threads to finish shutdown ...");
                for ( int currentThreadIdx = 0; currentThreadIdx < threadCount; currentThreadIdx++ )  {
                    final Thread currentThread = threadList[currentThreadIdx];

                    if (currentThread.isAlive()) {
                        log.logFine("publish: Waiting for remaining publishing thread '" + currentThread.getName() + "' to finish shutdown");
                        try { currentThread.join(500); }catch (InterruptedException ex) {}
                    }
                }

                log.logInfo("publish: Shutdown off all remaining publishing thread finished.");

            }
            catch (Exception ee) {
                log.logWarning("publish: Unexpected error while trying to shutdown all remaining publishing threads.",e);
            }

            return 0;
        }
    }

    public static HashMap getSeedUploadMethods() {
        synchronized (yacyCore.seedUploadMethods) {
            return (HashMap) yacyCore.seedUploadMethods.clone();
        }
    }

    public static yacySeedUploader getSeedUploader(String methodname) {
        String className = null;
        synchronized (yacyCore.seedUploadMethods) {
            if (yacyCore.seedUploadMethods.containsKey(methodname)) {
                className = (String) yacyCore.seedUploadMethods.get(methodname);
            }
        }

        if (className == null) { return null; }
        try {
            final Class uploaderClass = Class.forName(className);
            final Object uploader = uploaderClass.newInstance();
            return (yacySeedUploader) uploader;
        } catch (Exception e) {
            return null;
        }
    }

    public static void loadSeedUploadMethods() {
        final HashMap availableUploaders = new HashMap();
        try {
            final String uploadersPkgName = yacyCore.class.getPackage().getName() + ".seedUpload";
            final String packageURI = yacyCore.class.getResource("/"+uploadersPkgName.replace('.','/')).toString();

            // open the parser directory
            final File uploadersDir = new File(new URI(packageURI));
            if ((uploadersDir == null) || (!uploadersDir.exists()) || (!uploadersDir.isDirectory())) {
                yacyCore.seedUploadMethods.clear();
                changeSeedUploadMethod("none");
            }

            final String[] uploaderClasses = uploadersDir.list(new FilenameFilter() {
                public boolean accept(File dir, String name) {
                    return name.startsWith("yacySeedUpload") && name.endsWith(".class");
                }});

            final String javaClassPath = System.getProperty("java.class.path");

            if (uploaderClasses == null) { return; }
            for (int uploaderNr=0; uploaderNr<uploaderClasses.length; uploaderNr++) {
                final String className = uploaderClasses[uploaderNr].substring(0,uploaderClasses[uploaderNr].indexOf(".class"));
                final String fullClassName = uploadersPkgName + "." + className;
                try {
                    final Class uploaderClass = Class.forName(fullClassName);
                    final Object theUploader = uploaderClass.newInstance();
                    if (!(theUploader instanceof yacySeedUploader)) { continue; }
                    final String[] neededLibx = ((yacySeedUploader)theUploader).getLibxDependencies();
                    if (neededLibx != null) {
                        for (int libxId=0; libxId < neededLibx.length; libxId++) {
                            if (javaClassPath.indexOf(neededLibx[libxId]) == -1) {
                                throw new Exception("Missing dependency");
                            }
                        }
                    }
                    availableUploaders.put(className.substring("yacySeedUpload".length()),fullClassName);
                } catch (Exception e) { /* we can ignore this for the moment */
                } catch (Error e)     { /* we can ignore this for the moment */ }
            }
        } catch (Exception e) {

        } finally {
            synchronized (yacyCore.seedUploadMethods) {
                yacyCore.seedUploadMethods.clear();
                yacyCore.seedUploadMethods.putAll(availableUploaders);
            }
        }
    }

    public static boolean changeSeedUploadMethod(String method) {
        if (method == null || method.length() == 0) return false;

        if (method.equalsIgnoreCase("none")) return true;

        synchronized (yacyCore.seedUploadMethods) {
            return yacyCore.seedUploadMethods.containsKey(method);
        }
    }

    public String saveSeedList() {
        // return an error if this is not successful, and NULL if everything is fine
        return saveSeedList(this.switchboard);
    }

    public String saveSeedList(serverSwitch sb) {
        try {
            // return an error if this is not successful, and NULL if everything is fine
            String logt;

            // be shure that we have something to say
            if (seedDB.mySeed.getAddress() == null) {
                final String errorMsg = "We have no valid IP address until now";
                log.logWarning("SaveSeedList: " + errorMsg);
                return errorMsg;
            }

            // getting the configured seed uploader
            String seedUploadMethod = sb.getConfig("seedUploadMethod", "");

            // for backward compatiblity ....
            if ( seedUploadMethod.equalsIgnoreCase("Ftp") ||
                (seedUploadMethod.equals("") && sb.getConfig("seedFTPPassword", "").length() > 0)
            ) {
                seedUploadMethod = "Ftp";
                sb.setConfig("seedUploadMethod",seedUploadMethod);
            } else if ( seedUploadMethod.equalsIgnoreCase("File") ||
                       (seedUploadMethod.equals("") && sb.getConfig("seedFilePath", "").length() > 0)
            ) {
                seedUploadMethod = "File";
                sb.setConfig("seedUploadMethod",seedUploadMethod);
            }

            //  determine the seed uploader that should be used ...
            if (seedUploadMethod.equalsIgnoreCase("none")) { return "no uploader specified"; }

            yacySeedUploader uploader = getSeedUploader(seedUploadMethod);
            if (uploader == null) {
                final String errorMsg = "Unable to get the proper uploader-class for seed uploading method '" + seedUploadMethod + "'.";
                log.logWarning("SaveSeedList: " + errorMsg);
                return errorMsg;
            }

            // ensure that the seed file url is configured properly
            URL seedURL;
            try{
                final String seedURLStr = sb.getConfig("seedURL", "");
                if (seedURLStr.length() == 0) { throw new MalformedURLException("The seed-file url must not be empty."); }
                if (!(
                        seedURLStr.toLowerCase().startsWith("http://") ||
                        seedURLStr.toLowerCase().startsWith("https://")
                )){ 
                    throw new MalformedURLException("Unsupported protocol."); 
                }
                seedURL = new URL(seedURLStr);
            } catch(MalformedURLException e) {
                final String errorMsg = "Malformed seed file URL '" + sb.getConfig("seedURL", "") + "'. " + e.getMessage();
                log.logWarning("SaveSeedList: " + errorMsg);
                return errorMsg;
            }

            // upload the seed-list using the configured uploader class
            String prevStatus = seedDB.mySeed.get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_JUNIOR);
            if (prevStatus.equals(yacySeed.PEERTYPE_PRINCIPAL)) { prevStatus = yacySeed.PEERTYPE_SENIOR; }

            try {
                seedDB.mySeed.put(yacySeed.PEERTYPE, yacySeed.PEERTYPE_PRINCIPAL); // this information shall also be uploaded

                log.logFine("SaveSeedList: Using seed uploading method '" + seedUploadMethod + "' for seed-list uploading." +
                            "\n\tPrevious peerType is '" + seedDB.mySeed.get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_JUNIOR) + "'.");

//              logt = seedDB.uploadCache(seedFTPServer, seedFTPAccount, seedFTPPassword, seedFTPPath, seedURL);
                logt = seedDB.uploadCache(uploader,sb, seedDB, seedURL);
                if (logt != null) {
                    if (logt.indexOf("Error") >= 0) {
                        seedDB.mySeed.put(yacySeed.PEERTYPE, prevStatus);
                        final String errorMsg = "SaveSeedList: seed upload failed using " + uploader.getClass().getName() + " (error): " + logt.substring(logt.indexOf("Error") + 6);
                        log.logSevere(errorMsg);
                        return errorMsg;
                    }
                    log.logInfo(logt);
                }

                // finally, set the principal status
                sb.setConfig("yacyStatus", yacySeed.PEERTYPE_PRINCIPAL);
                return null;
            } catch (Exception e) {
                seedDB.mySeed.put(yacySeed.PEERTYPE, prevStatus);
                sb.setConfig("yacyStatus", prevStatus);
                final String errorMsg = "SaveSeedList: Seed upload failed (IO error): " + e.getMessage();
                log.logInfo(errorMsg,e);
                return errorMsg;
            }
        } finally {
            this.lastSeedUpload_seedDBSize = seedDB.sizeConnected();
            this.lastSeedUpload_timeStamp = System.currentTimeMillis();

            this.lastSeedUpload_myIP = seedDB.mySeed.get(yacySeed.IP, "127.0.0.1");
            //this.lastSeedUpload_myPeerType = seedDB.mySeed.get(yacySeed.PEERTYPE, yacySeed.PEERTYPE_JUNIOR);
        }
    }

}
