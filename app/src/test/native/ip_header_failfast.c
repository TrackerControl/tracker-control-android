/* Only link with ip_header_test: any dispatch beyond header validation must
 * fail immediately. These stand-ins must never be used for valid packets. */
#include <stdlib.h>
void ng_malloc(void) { abort(); }
void write_pcap_rec(void) { abort(); }
void ng_free(void) { abort(); }
void report_exit(void) { abort(); }
void ip6_skip_ext_headers(void) { abort(); }
void calc_checksum(void) { abort(); }
void route_flow_clear_verdict(void) { abort(); }
void route_flow_lookup(void) { abort(); }
void route_flow_lookup_verdict(void) { abort(); }
void route_dns_direct(void) { abort(); }
void route_wants_tunnel(void) { abort(); }
void get_uid_q(void) { abort(); }
void route_uid_relevant(void) { abort(); }
void block_udp(void) { abort(); }
void route_flow_store_verdict(void) { abort(); }
void write_wireguard_packet(void) { abort(); }
void handle_tcp(void) { abort(); }
void handle_icmp(void) { abort(); }
void handle_udp(void) { abort(); }
void create_packet(void) { abort(); }
void is_address_allowed(void) { abort(); }
void write_rst(void) { abort(); }
void parse_tls_header(void) { abort(); }
void is_tunnel_uid(void) { abort(); }
void route_default_is_tunnel(void) { abort(); }
void route_flow_store(void) { abort(); }
void write_tcp(void) { abort(); }
void hex2bytes(void) { abort(); }
