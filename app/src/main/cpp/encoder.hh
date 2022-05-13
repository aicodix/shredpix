/*
Encoder for COFDMTV

Copyright 2021 Ahmet Inan <inan@aicodix.de>
*/

#pragma once

#include <cmath>
#include <iostream>
#include <algorithm>
#include "bose_chaudhuri_hocquenghem_encoder.hh"
#include "base37_bitmap.hh"
#include "xorshift.hh"
#include "complex.hh"
#include "bitman.hh"
#include "polar.hh"
#include "utils.hh"
#include "const.hh"
#include "papr.hh"
#include "fft.hh"
#include "mls.hh"
#include "crc.hh"

struct Interface {
	virtual void configure(const uint8_t *, const int8_t *, int, int, bool) = 0;

	virtual bool produce(int16_t *, int) = 0;

	virtual int rate() = 0;

	virtual ~Interface() = default;
};

template<int RATE>
class Encoder : public Interface {
	typedef DSP::Complex<float> cmplx;
	typedef DSP::Const<float> Const;
	static const int symbol_length = (1280 * RATE) / 8000;
	static const int guard_length = symbol_length / 8;
	static const int extended_length = symbol_length + guard_length;
	static const int data_bits = 43040;
	static const int cor_seq_len = 127;
	static const int cor_seq_off = 1 - cor_seq_len;
	static const int cor_seq_poly = 0b10001001;
	static const int pre_seq_len = 255;
	static const int pre_seq_off = -pre_seq_len / 2;
	static const int fancy_off = -(8 * 9 * 3) / 2;
	static const int pre_seq_poly = 0b100101011;
	static const int pilot_poly = 0b100101011;
	DSP::FastFourierTransform<symbol_length, cmplx, 1> bwd;
	CODE::CRC<uint16_t> crc;
	CODE::BoseChaudhuriHocquenghemEncoder<255, 71> bch;
	ImprovePAPR<cmplx, symbol_length, RATE <= 16000 ? 4 : 1> improve_papr;
	Polar polar;
	cmplx temp[extended_length], freq[symbol_length], cons[32400], prev[512], guard[guard_length];
	uint8_t mesg[data_bits / 8], call[9];
	uint64_t meta_data;
	int pay_car_cnt = 0;
	int pay_car_off = 0;
	int carrier_offset = 0;
	int symbol_count = 0;
	int symbol_number = 0;
	int count_down = 0;
	int fancy_line = 0;

	static uint64_t base37(const int8_t *str) {
		uint64_t acc = 0;
		for (char c = *str++; c; c = *str++) {
			acc *= 37;
			if (c >= '0' && c <= '9')
				acc += c - '0' + 1;
			else if (c >= 'a' && c <= 'z')
				acc += c - 'a' + 11;
			else if (c >= 'A' && c <= 'Z')
				acc += c - 'A' + 11;
			else if (c != ' ')
				return -1;
		}
		return acc;
	}

	static uint8_t base37_map(int8_t c) {
		if (c >= '0' && c <= '9')
			return c - '0' + 1;
		if (c >= 'a' && c <= 'z')
			return c - 'a' + 11;
		if (c >= 'A' && c <= 'Z')
			return c - 'A' + 11;
		return 0;
	}

	static int nrz(bool bit) {
		return 1 - 2 * bit;
	}

	int bin(int carrier) {
		return (carrier + carrier_offset + symbol_length) % symbol_length;
	}

	void schmidl_cox() {
		CODE::MLS seq(cor_seq_poly);
		float factor = std::sqrt(float(2 * symbol_length) / cor_seq_len);
		for (int i = 0; i < symbol_length; ++i)
			freq[i] = 0;
		freq[bin(cor_seq_off - 2)] = factor;
		for (int i = 0; i < cor_seq_len; ++i)
			freq[bin(2 * i + cor_seq_off)] = nrz(seq());
		for (int i = 0; i < cor_seq_len; ++i)
			freq[bin(2 * i + cor_seq_off)] *= freq[bin(2 * (i - 1) + cor_seq_off)];
		transform(false);
	}

	void preamble() {
		uint8_t data[9] = {0}, parity[23] = {0};
		for (int i = 0; i < 55; ++i)
			CODE::set_be_bit(data, i, (meta_data >> i) & 1);
		crc.reset();
		uint16_t cs = crc(meta_data << 9);
		for (int i = 0; i < 16; ++i)
			CODE::set_be_bit(data, i + 55, (cs >> i) & 1);
		bch(data, parity);
		CODE::MLS seq(pre_seq_poly);
		float factor = std::sqrt(float(symbol_length) / pre_seq_len);
		for (int i = 0; i < symbol_length; ++i)
			freq[i] = 0;
		freq[bin(pre_seq_off - 1)] = factor;
		for (int i = 0; i < 71; ++i)
			freq[bin(i + pre_seq_off)] = nrz(CODE::get_be_bit(data, i));
		for (int i = 71; i < pre_seq_len; ++i)
			freq[bin(i + pre_seq_off)] = nrz(CODE::get_be_bit(parity, i - 71));
		for (int i = 0; i < pre_seq_len; ++i)
			freq[bin(i + pre_seq_off)] *= freq[bin(i - 1 + pre_seq_off)];
		for (int i = 0; i < pre_seq_len; ++i)
			freq[bin(i + pre_seq_off)] *= nrz(seq());
		transform();
	}

	void fancy_symbol() {
		int active_carriers = 1;
		for (int j = 0; j < 9; ++j)
			for (int i = 0; i < 8; ++i)
				active_carriers += (base37_bitmap[call[j] + 37 * (10 - fancy_line)] >> i) & 1;
		CODE::MLS seq(pilot_poly);
		float factor = std::sqrt(float(symbol_length) / active_carriers);
		for (int i = 0; i < symbol_length; ++i)
			freq[i] = 0;
		for (int j = 0; j < 9; ++j)
			for (int i = 0; i < 8; ++i)
				if (base37_bitmap[call[j] + 37 * (10 - fancy_line)] & (1 << (7 - i)))
					freq[bin((8 * j + i) * 3 + fancy_off)] = factor * nrz(seq());
		transform(false);
	}

	void pilot_block() {
		CODE::MLS seq(pilot_poly);
		float factor = std::sqrt(float(symbol_length) / pay_car_cnt);
		for (int i = 0; i < symbol_length; ++i)
			freq[i] = 0;
		for (int i = 0; i < pay_car_cnt; ++i)
			freq[bin(i + pay_car_off)] = prev[i] = factor * nrz(seq());
		transform();
	}

	void payload_symbol() {
		for (int i = 0; i < symbol_length; ++i)
			freq[i] = 0;
		for (int i = 0; i < pay_car_cnt; ++i)
			freq[bin(i + pay_car_off)] = prev[i] *= cons[pay_car_cnt * symbol_number + i];
		transform();
	}

	void silence() {
		for (int i = 0; i < symbol_length; ++i)
			temp[i] = 0;
	}

	void transform(bool papr_reduction = true) {
		if (papr_reduction && RATE <= 16000)
			improve_papr(freq);
		bwd(temp, freq);
		for (int i = 0; i < symbol_length; ++i)
			temp[i] /= std::sqrt(float(8 * symbol_length));
	}

	void prepare(int operation_mode) {
		switch (operation_mode) {
			case 6:
				pay_car_cnt = 432;
				symbol_count = 50;
				break;
			case 7:
				pay_car_cnt = 400;
				symbol_count = 54;
				break;
			case 8:
				pay_car_cnt = 400;
				symbol_count = 81;
				break;
			case 9:
				pay_car_cnt = 360;
				symbol_count = 90;
				break;
			case 10:
				pay_car_cnt = 512;
				symbol_count = 42;
				break;
			case 11:
				pay_car_cnt = 384;
				symbol_count = 56;
				break;
			case 12:
				pay_car_cnt = 384;
				symbol_count = 84;
				break;
			case 13:
				pay_car_cnt = 256;
				symbol_count = 126;
				break;
		}
		pay_car_off = -pay_car_cnt / 2;
		symbol_number = 0;
		fancy_line = 0;
	}
	void next_sample(int16_t *samples, cmplx signal, int channel, int i) {
		switch (channel) {
			case 1:
				samples[2 * i] = std::clamp<float>(std::nearbyint(32767 * signal.real()), -32768, 32767);
				samples[2 * i + 1] = 0;
				break;
			case 2:
				samples[2 * i] = 0;
				samples[2 * i + 1] = std::clamp<float>(std::nearbyint(32767 * signal.real()), -32768, 32767);
				break;
			case 4:
				samples[2 * i] = std::clamp<float>(std::nearbyint(32767 * signal.real()), -32768, 32767);
				samples[2 * i + 1] = std::clamp<float>(std::nearbyint(32767 * signal.imag()), -32768, 32767);
				break;
			default:
				samples[i] = std::clamp<float>(std::nearbyint(32767 * signal.real()), -32768, 32767);
		}
	}
public:
	Encoder() : crc(0xA8F4), bch({
		0b100011101, 0b101110111, 0b111110011, 0b101101001,
		0b110111101, 0b111100111, 0b100101011, 0b111010111,
		0b000010011, 0b101100101, 0b110001011, 0b101100011,
		0b100011011, 0b100111111, 0b110001101, 0b100101101,
		0b101011111, 0b111111001, 0b111000011, 0b100111001,
		0b110101001, 0b000011111, 0b110000111, 0b110110001}) {}

	int rate() final {
		return RATE;
	}

	bool produce(int16_t *audio_buffer, int channel_select) final {
		switch (count_down) {
			case 7:
				fancy_symbol();
				if (++fancy_line == 11)
					--count_down;
				break;
			case 6:
				pilot_block();
				--count_down;
				break;
			case 5:
				schmidl_cox();
				--count_down;
				break;
			case 4:
				preamble();
				--count_down;
				break;
			case 3:
				pilot_block();
				--count_down;
				break;
			case 2:
				payload_symbol();
				if (++symbol_number == symbol_count)
					--count_down;
				break;
			case 1:
				silence();
				--count_down;
			default:
				return false;
		}
		for (int i = 0; i < guard_length; ++i) {
			float x = i / float(guard_length - 1);
			float y = 0.5f * (1 - std::cos(DSP::Const<float>::Pi() * x));
			cmplx sum = DSP::lerp(guard[i], temp[i + symbol_length - guard_length], y);
			next_sample(audio_buffer, sum, channel_select, i);
		}
		for (int i = 0; i < guard_length; ++i)
			guard[i] = temp[i];
		for (int i = 0; i < symbol_length; ++i)
			next_sample(audio_buffer, temp[i], channel_select, i + guard_length);
		return true;
	}

	void configure(const uint8_t *payload, const int8_t *call_sign, int operation_mode, int carrier_frequency, bool fancy_header) final {
		carrier_offset = (carrier_frequency * symbol_length) / RATE;
		meta_data = (base37(call_sign) << 8) | operation_mode;
		for (int i = 0; i < 9; ++i)
			call[i] = 0;
		for (int i = 0; i < 9 && call_sign[i]; ++i)
			call[i] = base37_map(call_sign[i]);
		count_down = 6 + fancy_header;
		for (int i = 0; i < guard_length; ++i)
			guard[i] = 0;
		prepare(operation_mode);
		CODE::Xorshift32 scrambler;
		for (int i = 0; i < data_bits / 8; ++i)
			mesg[i] = payload[i] ^ scrambler();
		polar(cons, mesg, operation_mode);
	}
};
