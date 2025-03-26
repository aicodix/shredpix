/*
Polar encoder for COFDMTV

Copyright 2021 Ahmet Inan <inan@aicodix.de>
*/

#pragma once

#include <cmath>
#include "crc.hh"
#include "psk.hh"
#include "bitman.hh"
#include "complex.hh"
#include "polar_tables.hh"
#include "polar_encoder.hh"

class Polar {
	typedef DSP::Complex<float> cmplx;
	static const int data_bits = 43040;
	static const int crc_bits = data_bits + 32;
	CODE::CRC<uint32_t> crc;
	CODE::PolarSysEnc<int8_t> encode;
	int8_t code[65536], mesg[44096];
	const uint32_t *frozen_bits;
	int code_order = 0;
	int cons_bits = 0;
	int cons_cnt = 0;
	int mesg_bits = 0;
	int mod_bits = 0;

	static int nrz(bool bit) {
		return 1 - 2 * bit;
	}

	void shorten() {
		int code_bits = 1 << code_order;
		for (int i = 0, j = 0, k = 0; i < code_bits; ++i)
			if ((frozen_bits[i / 32] >> (i % 32)) & 1 || k++ < crc_bits)
				code[j++] = code[i];
	}

	cmplx mod_map(int8_t *b) {
		switch (mod_bits) {
			case 2:
				return PhaseShiftKeying<4, cmplx, int8_t>::map(b);
			case 3:
				return PhaseShiftKeying<8, cmplx, int8_t>::map(b);
		}
		return 0;
	}

	void prepare(int mode) {
		switch (mode) {
			case 6:
				mod_bits = 3;
				code_order = 16;
				cons_bits = 64800;
				mesg_bits = 43808;
				frozen_bits = frozen_64800_43072;
				break;
			case 7:
				mod_bits = 3;
				code_order = 16;
				cons_bits = 64800;
				mesg_bits = 43808;
				frozen_bits = frozen_64800_43072;
				break;
			case 8:
				mod_bits = 2;
				code_order = 16;
				cons_bits = 64800;
				mesg_bits = 43808;
				frozen_bits = frozen_64800_43072;
				break;
			case 9:
				mod_bits = 2;
				code_order = 16;
				cons_bits = 64800;
				mesg_bits = 43808;
				frozen_bits = frozen_64800_43072;
				break;
			case 10:
				mod_bits = 3;
				code_order = 16;
				cons_bits = 64512;
				mesg_bits = 44096;
				frozen_bits = frozen_64512_43072;
				break;
			case 11:
				mod_bits = 3;
				code_order = 16;
				cons_bits = 64512;
				mesg_bits = 44096;
				frozen_bits = frozen_64512_43072;
				break;
			case 12:
				mod_bits = 2;
				code_order = 16;
				cons_bits = 64512;
				mesg_bits = 44096;
				frozen_bits = frozen_64512_43072;
				break;
			case 13:
				mod_bits = 2;
				code_order = 16;
				cons_bits = 64512;
				mesg_bits = 44096;
				frozen_bits = frozen_64512_43072;
				break;
		}
		cons_cnt = cons_bits / mod_bits;
	}

public:
	Polar() : crc(0xD419CC15) {}

	void operator()(cmplx *cons, const uint8_t *message, int operation_mode) {
		prepare(operation_mode);

		for (int i = 0; i < data_bits; ++i)
			mesg[i] = nrz(CODE::get_le_bit(message, i));
		crc.reset();
		for (int i = 0; i < data_bits / 8; ++i)
			crc(message[i]);
		for (int i = 0; i < 32; ++i)
			mesg[i + data_bits] = nrz((crc() >> i) & 1);
		for (int i = crc_bits; i < mesg_bits; ++i)
			mesg[i] = 1;
		encode(code, mesg, frozen_bits, code_order);
		shorten();

		for (int i = 0; i < cons_cnt; ++i)
			cons[i] = mod_map(code + mod_bits * i);
	}
};
