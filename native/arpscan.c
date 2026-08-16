// arpscan - ARP scan a subnet, print "IP MAC" lines to stdout.
// Usage: arpscan <interface> <gateway_ip> <prefix_len>
// Linux-only (AF_PACKET). Statically compiled for Android ARM64.
#define _GNU_SOURCE
#include <arpa/inet.h>
#include <errno.h>
#include <net/if.h>
#include <net/if_arp.h>
#include <netinet/if_ether.h>
#include <netpacket/packet.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/socket.h>
#include <sys/time.h>
#include <time.h>
#include <unistd.h>

typedef struct {
    unsigned char  addr[6];
} mac_t;

static int  g_sock = -1;
static char g_ifname[IFNAMSIZ];
static mac_t g_own_mac;
static uint32_t g_own_ip;
static uint32_t g_net, g_mask, g_hostmask;
static int  g_nr_hosts;
static uint32_t *g_results_ip;
static mac_t *g_results_mac;
static int  g_result_count;

static uint32_t ip_from_str(const char *s) { return ntohl(inet_addr(s)); }

static void build_arp_frame(uint8_t *frame, uint16_t opcode,
                            uint32_t sender_ip, const mac_t *sender_mac,
                            uint32_t target_ip, const mac_t *target_mac) {
    struct ethhdr *eth = (struct ethhdr *)frame;
    struct arphdr *arp = (struct arphdr *)(frame + sizeof(struct ethhdr));
    unsigned char *payload = frame + sizeof(struct ethhdr) + sizeof(struct arphdr);

    memset(eth->h_dest, 0xff, 6);
    memcpy(eth->h_source, sender_mac, 6);
    eth->h_proto = htons(ETH_P_ARP);

    arp->ar_hrd = htons(ARPHRD_ETHER);
    arp->ar_pro = htons(ETH_P_IP);
    arp->ar_hln = 6;
    arp->ar_pln = 4;
    arp->ar_op  = htons(opcode);

    memcpy(payload, sender_mac, 6);          // sender hw
    memcpy(payload + 6, &sender_ip, 4);      // sender proto
    memcpy(payload + 10, target_mac, 6);     // target hw
    memcpy(payload + 16, &target_ip, 4);     // target proto
}

static int parse_arp_packet(const uint8_t *buf, int len,
                            uint32_t *out_ip, mac_t *out_mac) {
    if (len < (int)(sizeof(struct ethhdr) + sizeof(struct arphdr) + 20))
        return -1;
    struct ethhdr *eth = (struct ethhdr *)buf;
    if (eth->h_proto != htons(ETH_P_ARP))
        return -1;
    struct arphdr *arp = (struct arphdr *)(buf + sizeof(struct ethhdr));
    if (arp->ar_op != htons(ARPOP_REPLY) && arp->ar_op != htons(ARPOP_REQUEST))
        return -1;
    if (arp->ar_hln != 6 || arp->ar_pln != 4 || arp->ar_hrd != htons(ARPHRD_ETHER))
        return -1;
    const unsigned char *p = buf + sizeof(struct ethhdr) + sizeof(struct arphdr);
    mac_t sender_mac;
    uint32_t sender_ip;
    memcpy(&sender_mac, p, 6);
    memcpy(&sender_ip, p + 6, 4);
    *out_ip = ntohl(sender_ip);
    memcpy(out_mac->addr, sender_mac.addr, 6);
    return 0;
}

static int dedupe(uint32_t ip) {
    for (int i = 0; i < g_result_count; i++)
        if (g_results_ip[i] == ip) return 1;
    return 0;
}

static void record(uint32_t ip, const mac_t *mac) {
    if (g_result_count >= g_nr_hosts) return;
    if (!(ip & g_hostmask)) return;          // skip network addr
    if ((ip | g_hostmask) == 0xffffffffu) return; // skip broadcast
    if (ip == g_own_ip) return;              // skip self
    if (dedupe(ip)) return;
    g_results_ip[g_result_count] = ip;
    g_results_mac[g_result_count] = *mac;
    g_result_count++;
}

static void send_request(uint32_t target_ip) {
    uint8_t frame[60];
    memset(frame, 0, sizeof(frame));
    mac_t zero_mac = {{0, 0, 0, 0, 0, 0}};
    uint32_t ip_n = htonl(target_ip);
    build_arp_frame(frame, ARPOP_REQUEST, g_own_ip, &g_own_mac, ip_n, &zero_mac);

    struct sockaddr_ll sll;
    memset(&sll, 0, sizeof(sll));
    sll.sll_family = AF_PACKET;
    sll.sll_protocol = htons(ETH_P_ARP);
    sll.sll_ifindex = if_nametoindex(g_ifname);
    sll.sll_halen = 6;
    memset(sll.sll_addr, 0xff, 6); // broadcast target hw addr

    sendto(g_sock, frame, sizeof(frame), 0, (struct sockaddr *)&sll, sizeof(sll));
}

static int collect(int timeout_ms) {
    struct timeval tv = { timeout_ms / 1000, (timeout_ms % 1000) * 1000 };
    setsockopt(g_sock, SOL_SOCKET, SO_RCVTIMEO, &tv, sizeof(tv));
    uint8_t buf[2048];
    for (;;) {
        ssize_t n = recvfrom(g_sock, buf, sizeof(buf), 0, NULL, NULL);
        if (n < 0) {
            if (errno == EAGAIN || errno == EWOULDBLOCK) return 0;
            return -1;
        }
        uint32_t ip;
        mac_t mac;
        if (parse_arp_packet(buf, (int)n, &ip, &mac) == 0)
            record(ip, &mac);
    }
}

int main(int argc, char **argv) {
    if (argc < 4) {
        fprintf(stderr, "usage: %s <interface> <gateway_ip> <prefix_len>\n", argv[0]);
        return 1;
    }
    strncpy(g_ifname, argv[1], sizeof(g_ifname) - 1);
    uint32_t gw = ip_from_str(argv[2]);
    if (gw == 0xffffffffu && strcmp(argv[2], "255.255.255.255") != 0) {
        fprintf(stderr, "invalid gateway ip\n");
        return 1;
    }
    int prefix = atoi(argv[3]);
    if (prefix < 1 || prefix > 30) {
        fprintf(stderr, "invalid prefix length\n");
        return 1;
    }

    g_mask = prefix == 32 ? 0xffffffffu : (~0u << (32 - prefix));
    g_hostmask = ~g_mask;
    g_net = gw & g_mask;
    g_nr_hosts = (int)(0x01u << (32 - prefix)) - 1;
    if (g_nr_hosts <= 0 || g_nr_hosts > 65534) {
        fprintf(stderr, "network too large to scan\n");
        return 1;
    }

    g_results_ip = malloc(sizeof(uint32_t) * (size_t)g_nr_hosts);
    g_results_mac = malloc(sizeof(mac_t) * (size_t)g_nr_hosts);
    if (!g_results_ip || !g_results_mac) {
        fprintf(stderr, "out of memory\n");
        return 1;
    }
    g_result_count = 0;

    g_sock = socket(AF_PACKET, SOCK_RAW, htons(ETH_P_ARP));
    if (g_sock < 0) {
        perror("socket");
        return 1;
    }

    int ifindex = if_nametoindex(g_ifname);
    if (!ifindex) {
        perror("if_nametoindex");
        return 1;
    }

    struct ifreq ifr;
    memset(&ifr, 0, sizeof(ifr));
    strncpy(ifr.ifr_name, g_ifname, IFNAMSIZ - 1);
    if (ioctl(g_sock, SIOCGIFHWADDR, &ifr) < 0) { perror("ioctl hwaddr"); return 1; }
    memcpy(g_own_mac.addr, ifr.ifr_hwaddr.sa_data, 6);

    if (ioctl(g_sock, SIOCGIFADDR, &ifr) < 0) { perror("ioctl ifaddr"); return 1; }
    struct sockaddr_in *sin = (struct sockaddr_in *)&ifr.ifr_addr;
    g_own_ip = ntohl(sin->sin_addr.s_addr);

    fprintf(stderr, "scanning %d hosts on %s/%d, if=%s\n",
            g_nr_hosts, argv[2], prefix, g_ifname);

    // Broadcast probe first to populate neighbor cache; some chips only answer to it.
    send_request(0xffffffffu);
    usleep(50000);

    for (int round = 0; round < 3; round++) {
        for (uint32_t ip = g_net + 1; ip < g_net + (1u << (32 - prefix)) - 1; ip++) {
            send_request(ip);
            if ((ip & 0x3f) == 0) usleep(1000);
        }
        collect(700 - round * 100);
    }
    collect(1500);

    for (int i = 0; i < g_result_count; i++) {
        char ipstr[INET_ADDRSTRLEN];
        struct in_addr a;
        a.s_addr = htonl(g_results_ip[i]);
        inet_ntop(AF_INET, &a, ipstr, sizeof(ipstr));
        printf("%s %02x:%02x:%02x:%02x:%02x:%02x\n", ipstr,
               g_results_mac[i].addr[0], g_results_mac[i].addr[1],
               g_results_mac[i].addr[2], g_results_mac[i].addr[3],
               g_results_mac[i].addr[4], g_results_mac[i].addr[5]);
    }
    return 0;
}