#include "utils.h"
#include "crypto/sha2.h"

#include <vector>
#include <string.h>
#include <unistd.h>
#include <stdio.h>

#ifdef WIN32
	// MinGW doesnt have this line (copied from Wine) for licensing reasons
	#define AI_V4MAPPED 0x00000800
	#include <winsock2.h>
	#include <ws2tcpip.h>
#else // WIN32
	#include <arpa/inet.h>
	#include <netinet/in.h>
	#include <netinet/tcp.h>
	#include <netdb.h>
	#include <fcntl.h>
	#include <sys/socket.h>
#endif // !WIN32

/***************************
 **** Varint processing ****
 ***************************/
void move_forward(std::vector<unsigned char>::const_iterator& it, size_t i, const std::vector<unsigned char>::const_iterator& end) {
	if (it > end-i)
		throw read_exception();
	std::advance(it, i);
}

uint64_t read_varint(std::vector<unsigned char>::const_iterator& it, const std::vector<unsigned char>::const_iterator& end) {
	move_forward(it, 1, end);
	uint8_t first = *(it-1);
	if (first < 0xfd)
		return first;
	else if (first == 0xfd) {
		move_forward(it, 2, end);
		return ((*(it-1) << 8) | *(it-2));
	} else if (first == 0xfe) {
		move_forward(it, 4, end);
		return ((*(it-1) << 24) | (*(it-2) << 16) | (*(it-3) << 8) | *(it-4));
	} else {
		move_forward(it, 8, end);
		return ((uint64_t(*(it-1)) << 56) |
						(uint64_t(*(it-2)) << 48) |
						(uint64_t(*(it-3)) << 40) |
						(uint64_t(*(it-4)) << 32) |
						(uint64_t(*(it-5)) << 24) |
						(uint64_t(*(it-6)) << 16) |
						(uint64_t(*(it-7)) << 8) |
						 uint64_t(*(it-8)));
	}
}

std::vector<unsigned char> varint(uint32_t size) {
	if (size < 0xfd) {
		uint8_t lesize = size;
		return std::vector<unsigned char>(&lesize, &lesize + sizeof(lesize));
	} else {
		std::vector<unsigned char> res;
		if (size <= 0xffff) {
			res.push_back(0xfd);
			uint16_t lesize = htole16(size);
			res.insert(res.end(), (unsigned char*)&lesize, ((unsigned char*)&lesize) + sizeof(lesize));
		} else if (size <= 0xffffffff) {
			res.push_back(0xfe);
			uint32_t lesize = htole32(size);
			res.insert(res.end(), (unsigned char*)&lesize, ((unsigned char*)&lesize) + sizeof(lesize));
		} else {
			res.push_back(0xff);
			uint64_t lesize = htole64(size);
			res.insert(res.end(), (unsigned char*)&lesize, ((unsigned char*)&lesize) + sizeof(lesize));
		}
		return res;
	}
}




/***********************
 **** Network utils ****
 ***********************/
ssize_t read_all(int filedes, char *buf, size_t nbyte) {
	if (nbyte <= 0)
		return 0;

	ssize_t count = 0;
	size_t total = 0;
	while (total < nbyte && (count = read(filedes, buf + total, nbyte-total)) > 0)
		total += count;
	if (count <= 0)
		return count;
	else
		return total;
}

ssize_t send_all(int filedes, const char *buf, size_t nbyte) {
	ssize_t count = 0;
	size_t total = 0;
	while (total < nbyte && (count = send(filedes, buf + total, nbyte-total, MSG_NOSIGNAL)) > 0)
		total += count;
	if (count <= 0)
		return count;
	else
		return total;
}

std::string gethostname(struct sockaddr_in6 *addr) {
	char hbuf[NI_MAXHOST];
	if (getnameinfo((struct sockaddr*) addr, sizeof(*addr), hbuf, sizeof(hbuf), NULL, 0, NI_NUMERICHOST))
		return "Unknown host";

	std::string res(hbuf);
	res += "/";
	if (getnameinfo((struct sockaddr*) addr, sizeof(*addr), hbuf, sizeof(hbuf), NULL, 0, NI_NAMEREQD))
		return res;
	else
		return res + std::string(hbuf);
}

bool lookup_address(const char* addr, struct sockaddr_in6* res) {
	struct addrinfo hints,*server = NULL;

	memset(&hints, 0, sizeof(hints));
#ifdef X86_BSD
	hints.ai_flags = 0;
	hints.ai_family = AF_INET;
#else
	hints.ai_flags = AI_V4MAPPED;
	hints.ai_family = AF_INET6;
#endif

	int gaires = getaddrinfo(addr, NULL, &hints, &server);
	if (gaires) {
		printf("Unable to lookup hostname: %d (%s)\n", gaires, gai_strerror(gaires));
		freeaddrinfo(server);
		return false;
	} else if (server->ai_addrlen != sizeof(*res)) {
		freeaddrinfo(server);
		return false;
	}
	memset((void*)res, 0, sizeof(*res));
	res->sin6_family = AF_INET6;
#ifdef X86_BSD
	struct sockaddr_in * in4 = (struct sockaddr_in *)server->ai_addr;
	uint8_t * p4 = (uint8_t *) &(in4->sin_addr);
	uint8_t * p6 = (uint8_t *) &(res->sin6_addr);
	for (int i=0; i < 10; i++)
		p6[i] = 0x00;
	p6[10] = 0xff;
	p6[11] = 0xff;
	for (int i=0; i < 4; i++)
		p6[12+i] = p4[i];
	res->sin6_family = AF_INET6;
#else
	res->sin6_addr = ((struct sockaddr_in6*)server->ai_addr)->sin6_addr;
#endif

	freeaddrinfo(server);
	return true;
}

void prepare_message(const char* command, unsigned char* headerAndData, size_t datalen) {
	struct bitcoin_msg_header *header = (struct bitcoin_msg_header*)headerAndData;

	memset(header->command, 0, sizeof(header->command));
	strcpy(header->command, command);

	header->length = htole32(datalen);
	header->magic = BITCOIN_MAGIC;

	unsigned char fullhash[32];
	double_sha256(headerAndData + sizeof(struct bitcoin_msg_header), fullhash, datalen);
	memcpy(header->checksum, fullhash, sizeof(header->checksum));
}

/********************
 *** Random stuff ***
 ********************/
#ifdef SHA256
extern "C" void SHA256(void *, uint32_t[8], uint64_t);
#endif

void static inline WriteBE64(unsigned char *ptr, uint64_t x) {
	ptr[0] = x >> 56; ptr[1] = x >> 48; ptr[2] = x >> 40; ptr[3] = x >> 32;
	ptr[4] = x >> 24; ptr[5] = x >> 16; ptr[6] = x >> 8; ptr[7] = x;
}

void static inline WriteBE32(unsigned char *ptr, uint32_t x) {
	ptr[0] = x >> 24; ptr[1] = x >> 16; ptr[2] = x >> 8; ptr[3] = x;
}

void static inline sha256_init(uint32_t state[8]) {
	state[0] = 0x6a09e667ul;
	state[1] = 0xbb67ae85ul;
	state[2] = 0x3c6ef372ul;
	state[3] = 0xa54ff53aul;
	state[4] = 0x510e527ful;
	state[5] = 0x9b05688cul;
	state[6] = 0x1f83d9abul;
	state[7] = 0x5be0cd19ul;
}

void static inline sha256_done(unsigned char* res, uint32_t state[8]) {
	WriteBE32(res     , state[0]);
	WriteBE32(res +  4, state[1]);
	WriteBE32(res +  8, state[2]);
	WriteBE32(res + 12, state[3]);
	WriteBE32(res + 16, state[4]);
	WriteBE32(res + 20, state[5]);
	WriteBE32(res + 24, state[6]);
	WriteBE32(res + 28, state[7]);
}

void double_sha256(const unsigned char* input, unsigned char* res, uint64_t byte_count) {
#ifndef SHA256
	CSHA256 hash;
	if (byte_count)
		hash.Write(input, byte_count);
	hash.Finalize(res);
	hash.Reset().Write(res, 32).Finalize(res);
#else
	uint64_t pad_count = 1 + ((119 - (byte_count % 64)) % 64);
	std::vector<unsigned char> data(byte_count + pad_count + 8);

	memcpy(&data[0], input, byte_count);
	data[byte_count] = 0x80;
	memset(&data[byte_count+1], 0, pad_count-1);
	WriteBE64(&data[byte_count + pad_count], byte_count << 3);

	uint32_t state[8];
	sha256_init(state);

	assert((byte_count + pad_count + 8) % 64 == 0);
	SHA256(&data[0], state, (byte_count + pad_count + 8) / 64);
	sha256_done(&data[0], state);

	data[32] = 0x80;
	memset(&data[32 + 1], 0, 32 - 8 - 1);
	WriteBE64(&data[64 - 8], 32 << 3);
	sha256_init(state);

	SHA256(&data[0], state, 1);
	sha256_done(res, state);
#endif
}

void double_sha256_two_32_inputs(const unsigned char* input, const unsigned char* input2, unsigned char* res) {
#ifndef SHA256
	CSHA256 hash;
	hash.Write(input, 32).Write(input2, 32).Finalize(res);
	hash.Reset().Write(res, 32).Finalize(res);
#else
	unsigned char data[128];

	memcpy(data,      input,  32);
	memcpy(data + 32, input2, 32);
	data[64] = 0x80;
	memset(data + 64 + 1, 0, 64 - 8 - 1);
	WriteBE64(data + 128 - 8, 64 << 3);

	uint32_t state[8];
	sha256_init(state);

	SHA256(&data[0], state, 2);
	sha256_done(data, state);

	data[32] = 0x80;
	memset(data + 32 + 1, 0, 32 - 8 - 1);
	WriteBE64(data + 64 - 8, 32 << 3);
	sha256_init(state);

	SHA256(data, state, 1);
	sha256_done(res, state);
#endif

}

void getblockhash(std::vector<unsigned char>& hashRes, const std::vector<unsigned char>& block, size_t offset) {
	assert(hashRes.size() == 32);
	return double_sha256(&block[offset], &hashRes[0], 80);
}

void print_hash(const unsigned char* hash) {
	for (int i = 31; i >= 0; i--)
		printf("%02x", hash[i]);
}

void do_assert(bool flag, const char* file, unsigned long line) {
	if (!flag) {
		printf("Assertion failed: %s:%lu\n", file, line);
		exit(1);
	}
}
