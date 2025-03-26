/*
SIMD wrapper used by polar encoder and decoder

Copyright 2020 Ahmet Inan <inan@aicodix.de>
*/

#pragma once

namespace CODE {

template <typename TYPE>
struct PolarHelper
{
	typedef TYPE PATH;
	static TYPE one()
	{
		return 1;
	}
	static TYPE zero()
	{
		return 0;
	}
	static TYPE signum(TYPE v)
	{
		return (v > 0) - (v < 0);
	}
	template <typename IN>
	static TYPE quant(IN in)
	{
		return in;
	}
	static TYPE qabs(TYPE a)
	{
		return std::abs(a);
	}
	static TYPE qmin(TYPE a, TYPE b)
	{
		return std::min(a, b);
	}
	static TYPE qadd(TYPE a, TYPE b)
	{
		return a + b;
	}
	static TYPE qmul(TYPE a, TYPE b)
	{
		return a * b;
	}
	static TYPE prod(TYPE a, TYPE b)
	{
		return signum(a) * signum(b) * qmin(qabs(a), qabs(b));
	}
	static TYPE madd(TYPE a, TYPE b, TYPE c)
	{
		return a * b + c;
	}
};

template <>
struct PolarHelper<int8_t>
{
	typedef int PATH;
	static int8_t one()
	{
		return 1;
	}
	static int8_t zero()
	{
		return 0;
	}
	static int8_t signum(int8_t v)
	{
		return (v > 0) - (v < 0);
	}
	template <typename IN>
	static int8_t quant(IN in)
	{
		return std::min<IN>(std::max<IN>(std::nearbyint(in), -127), 127);
	}
	static int8_t qabs(int8_t a)
	{
		return std::abs(std::max<int8_t>(a, -127));
	}
	static int8_t qmin(int8_t a, int8_t b)
	{
		return std::min(a, b);
	}
	static int8_t qadd(int8_t a, int8_t b)
	{
		return std::min<int16_t>(std::max<int16_t>(int16_t(a) + int16_t(b), -127), 127);
	}
	static int8_t qmul(int8_t a, int8_t b)
	{
		// return std::min<int16_t>(std::max<int16_t>(int16_t(a) * int16_t(b), -127), 127);
		// only used for hard decision values anyway
		return a * b;
	}
	static int8_t prod(int8_t a, int8_t b)
	{
		return signum(a) * signum(b) * qmin(qabs(a), qabs(b));
	}
	static int8_t madd(int8_t a, int8_t b, int8_t c)
	{
		return std::min<int16_t>(std::max<int16_t>(int16_t(a) * int16_t(b) + int16_t(c), -127), 127);
	}
};

}

