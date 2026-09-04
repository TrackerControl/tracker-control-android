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

#include "dhcp_options.h"

#define DHCP_OPTION_PAD 0
#define DHCP_OPTION_MESSAGE_TYPE 53
#define DHCP_OPTION_END 255

int dhcp_message_type(const uint8_t *options, size_t length) {
    if (options == NULL)
        return -1;

    size_t offset = 0;
    while (offset < length) {
        uint8_t code = options[offset++];

        if (code == DHCP_OPTION_PAD)
            continue;
        if (code == DHCP_OPTION_END)
            return -1;
        if (offset >= length)
            return -1;

        uint8_t option_length = options[offset++];
        if ((size_t) option_length > length - offset)
            return -1;

        if (code == DHCP_OPTION_MESSAGE_TYPE)
            return option_length == 1 ? options[offset] : -1;

        offset += option_length;
    }

    return -1;
}

int dhcp_reply_type(int request_type) {
    if (request_type == DHCP_MESSAGE_DISCOVER)
        return DHCP_MESSAGE_OFFER;
    if (request_type == DHCP_MESSAGE_REQUEST)
        return DHCP_MESSAGE_ACK;
    return -1;
}
