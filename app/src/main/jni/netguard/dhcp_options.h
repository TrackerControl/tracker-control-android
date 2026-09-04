/*
    This file is part of NetGuard.

    NetGuard is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    NetGuard is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with NetGuard.  If not, see <http://www.gnu.org/licenses/>.

    Copyright 2015-2019 by Marcel Bokhorst (M66B)
*/

#ifndef DHCP_OPTIONS_H
#define DHCP_OPTIONS_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define DHCP_MESSAGE_DISCOVER 1
#define DHCP_MESSAGE_OFFER 2
#define DHCP_MESSAGE_REQUEST 3
#define DHCP_MESSAGE_ACK 5

/*
 * Reads DHCP message type option 53 from the variable-length options field.
 * Returns the message type, or -1 when option 53 is absent or malformed.
 * Kept independent of netguard.h so the parser can be host-tested.
 */
int dhcp_message_type(const uint8_t *options, size_t length);

/* Returns the reply type for a supported client message, or -1 otherwise. */
int dhcp_reply_type(int request_type);

#ifdef __cplusplus
}
#endif

#endif /* DHCP_OPTIONS_H */
