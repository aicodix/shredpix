/*
Encoder for COFDMTV

Copyright 2021 Ahmet Inan <inan@aicodix.de>
*/

package com.aicodix.shredpix;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;

import com.aicodix.shredpix.databinding.ActivityMainBinding;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;

public class MainActivity extends AppCompatActivity {

	// Used to load the 'shredpix' library on application startup.
	static {
		System.loadLibrary("shredpix");
	}

	private final int payloadSize = 5380;
	private boolean lossyCompression;
	private boolean fancyHeader;
	private int noiseSymbols;
	private int sampleRate;
	private int channelSelect;
	private int operationMode;
	private int carrierFrequency;
	private int minCarrierFrequency;
	private int maxCarrierFrequency;
	private String callSign;
	private String imageFormat;
	private String pixelCount;
	private AudioTrack audioTrack;
	private short[] audioBuffer;
	private byte[] payload;
	private boolean doRecode;
	private boolean encoderOkay;
	private boolean payloadOkay;
	private int orientation;
	private Bitmap sourceBitmap;
	private Bitmap resizedBitmap;
	private ActivityMainBinding binding;
	private Handler handler;
	private Menu menu;

	private native boolean createEncoder(int sampleRate);

	private native void configureEncoder(byte[] payload, byte[] callSign, int operationMode, int carrierFrequency, int noiseSymbols, boolean fancyHeader);

	private native boolean produceEncoder(short[] audioBuffer, int channelSelect);

	private native void destroyEncoder();

	private final AudioTrack.OnPlaybackPositionUpdateListener audioListener = new AudioTrack.OnPlaybackPositionUpdateListener() {
		@Override
		public void onMarkerReached(AudioTrack ignore) {

		}

		@Override
		public void onPeriodicNotification(AudioTrack audioTrack) {
			if (produceEncoder(audioBuffer, channelSelect)) {
				audioTrack.write(audioBuffer, 0, audioBuffer.length);
			} else {
				audioTrack.stop();
				doneSending();
			}
		}
	};

	private void initAudioTrack() {
		if (audioTrack != null) {
			boolean rateChanged = audioTrack.getSampleRate() != sampleRate;
			boolean channelChanged = audioTrack.getChannelCount() != (channelSelect == 0 ? 1 : 2);
			if (!rateChanged && !channelChanged)
				return;
			audioTrack.stop();
			audioTrack.release();
		}
		int channelConfig = AudioFormat.CHANNEL_OUT_MONO;
		int channelCount = 1;
		if (channelSelect != 0) {
			channelCount = 2;
			channelConfig = AudioFormat.CHANNEL_OUT_STEREO;
		}
		int audioFormat = AudioFormat.ENCODING_PCM_16BIT;
		int sampleSize = 2;
		int symbolLength = (1280 * sampleRate) / 8000;
		int guardLength = symbolLength / 8;
		int extendedLength = symbolLength + guardLength;
		int bufferSize = 5 * extendedLength * sampleSize * channelCount;
		audioTrack = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, channelConfig, audioFormat, bufferSize, AudioTrack.MODE_STREAM);
		audioBuffer = new short[extendedLength * channelCount];
		audioTrack.setPlaybackPositionUpdateListener(audioListener);
		audioTrack.setPositionNotificationPeriod(extendedLength);
	}

	private void initEncoder() {
		encoderOkay = createEncoder(sampleRate);
		int icon = R.drawable.outline_send_24;
		if (!payloadOkay)
			icon = R.drawable.outline_disc_full_24;
		if (!encoderOkay)
			icon = R.drawable.outline_error_outline_24;
		menu.findItem(R.id.action_encode).setIcon(icon);
		menu.findItem(R.id.action_encode).setEnabled(payloadOkay && encoderOkay);
	}

	private InputStream openStream(Intent intent) {
		String action = intent.getAction();
		if (action == null)
			return null;
		if (!action.equals(Intent.ACTION_SEND))
			return null;
		String type = intent.getType();
		if (type == null)
			return null;
		if (!type.startsWith("image/"))
			return null;
		Uri uri = intent.getData();
		if (intent.hasExtra(Intent.EXTRA_STREAM))
			uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
		if (uri == null)
			return null;
		ContentResolver resolver = getContentResolver();
		InputStream stream;
		try {
			stream = resolver.openInputStream(uri);
		} catch (Exception ignore) {
			return null;
		}
		orientation = 0;
		try {
			Cursor cursor = resolver.query(uri, new String[]{MediaStore.Images.ImageColumns.ORIENTATION}, null, null, null);
			if (cursor != null) {
				if (cursor.moveToFirst())
					orientation = cursor.getInt(0);
				cursor.close();
			}
		} catch (Exception ignore) {
		}
		return stream;
	}

	private Bitmap decodeStream(InputStream stream) {
		BitmapFactory.Options options = new BitmapFactory.Options();
		options.inJustDecodeBounds = true;
		boolean useStream = true;
		try {
			int size = stream.available();
			if (size > 0 && size <= payloadSize) {
				payload = new byte[payloadSize];
				if (size == stream.read(payload, 0, size)) {
					for (int i = size; i < payloadSize; ++i)
						payload[i] = 0;
					BitmapFactory.decodeByteArray(payload, 0, payload.length, options);
					useStream = false;
				} else {
					return null;
				}
			}
		} catch (IOException ignore) {
		}
		if (useStream) {
			int bufferBytes = 1 << 20;
			if (!stream.markSupported())
				stream = new BufferedInputStream(stream, bufferBytes);
			stream.mark(bufferBytes);
			BitmapFactory.decodeStream(stream, null, options);
		}
		String type = options.outMimeType;
		if (type == null)
			return null;
		if (!(type.equals("image/jpeg") || type.equals("image/png") || type.equals("image/webp")))
			return null;
		int minLength = 16;
		if (Math.min(options.outWidth, options.outHeight) < minLength)
			return null;
		try {
			if (useStream)
				stream.reset();
		} catch (Exception ignore) {
			return null;
		}
		int maxLength = 1024;
		boolean recode = Math.max(options.outWidth, options.outHeight) > maxLength;
		if (recode) {
			options.inSampleSize = 1;
			while (Math.max(options.outWidth, options.outHeight) / options.inSampleSize > maxLength)
				options.inSampleSize *= 2;
		}
		options.inJustDecodeBounds = false;
		Bitmap bitmap;
		if (useStream)
			bitmap = BitmapFactory.decodeStream(stream, null, options);
		else
			bitmap = BitmapFactory.decodeByteArray(payload, 0, payload.length, options);
		if (bitmap == null)
			return null;
		if (!recode && useStream) {
			try {
				int size = stream.available();
				if (size > 0 && size <= payloadSize) {
					stream.reset();
					payload = new byte[payloadSize];
					if (size == stream.read(payload, 0, size)) {
						for (int i = size; i < payloadSize; ++i)
							payload[i] = 0;
					} else {
						recode = true;
					}
				} else {
					recode = true;
				}
			} catch (IOException ignore) {
				recode = true;
			}
		}
		enableRecoding(recode);
		if (recode && orientation > 0) {
			int width = bitmap.getWidth();
			int height = bitmap.getHeight();
			Matrix matrix = new Matrix();
			matrix.postRotate(orientation);
			bitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
		}
		return bitmap;
	}

	private Bitmap resizeBitmap(Bitmap bitmap) {
		int pixelsMax;
		switch (pixelCount) {
			case "1M":
				pixelsMax = 1 << 20;
				break;
			case "512K":
				pixelsMax = 1 << 19;
				break;
			case "256K":
				pixelsMax = 1 << 18;
				break;
			case "128K":
				pixelsMax = 1 << 17;
				break;
			case "64K":
				pixelsMax = 1 << 16;
				break;
			case "32K":
				pixelsMax = 1 << 15;
				break;
			case "16K":
				pixelsMax = 1 << 14;
				break;
			default:
				return null;
		}
		int width = bitmap.getWidth();
		int height = bitmap.getHeight();
		while (width * height > pixelsMax) {
			width /= 2;
			height /= 2;
		}
		return Bitmap.createScaledBitmap(bitmap, width, height, true);
	}

	private byte[] encodeBitmap(Bitmap bitmap) {
		Bitmap.CompressFormat format;
		boolean bisect = true;
		int lowerQuality = 0;
		int higherQuality = 100;
		int quality = lowerQuality;
		switch (imageFormat) {
			case "JPEG":
				format = Bitmap.CompressFormat.JPEG;
				break;
			case "PNG":
				format = Bitmap.CompressFormat.PNG;
				bisect = false;
				break;
			case "WebP":
				format = Bitmap.CompressFormat.WEBP;
				if (lossyCompression) {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
						format = Bitmap.CompressFormat.WEBP_LOSSY;
					else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q)
						higherQuality = 99;
				} else {
					if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
						format = Bitmap.CompressFormat.WEBP_LOSSLESS;
					else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q)
						quality = 100;
					else
						return null;
					bisect = false;
				}
				break;
			default:
				return null;
		}
		ByteArrayOutputStream stream = new ByteArrayOutputStream(payloadSize);
		if (!bitmap.compress(format, quality, stream))
			return null;
		if (bisect && stream.size() <= payloadSize) {
			int testQuality = higherQuality;
			ByteArrayOutputStream testStream = new ByteArrayOutputStream(payloadSize);
			if (!bitmap.compress(format, testQuality, testStream))
				return null;
			if (testStream.size() <= payloadSize) {
				stream = testStream;
//				quality = testQuality;
			} else {
				for (testQuality = (lowerQuality + higherQuality + 1) / 2; testQuality != quality && testQuality != lowerQuality && testQuality != higherQuality; testQuality = (lowerQuality + higherQuality + 1) / 2) {
					testStream = new ByteArrayOutputStream(payloadSize);
					if (!bitmap.compress(format, testQuality, testStream))
						return null;
					if (testStream.size() > payloadSize) {
						higherQuality = testQuality;
						if (testStream.size() < stream.size()) {
							stream = testStream;
							quality = testQuality;
						}
					} else {
						lowerQuality = testQuality;
						if (testStream.size() > stream.size() || stream.size() > payloadSize) {
							stream = testStream;
							quality = testQuality;
						}
					}
				}
			}
		}
		while (stream.size() < payloadSize)
			stream.write(0);
		return stream.toByteArray();
	}

	@Override
	protected void onNewIntent(Intent intent) {
		super.onNewIntent(intent);
		InputStream stream = openStream(intent);
		if (stream == null)
			return;
		Bitmap bitmap = decodeStream(stream);
		if (bitmap == null)
			return;
		sourceBitmap = bitmap;
		sourceBitmap.setHasAlpha(false);
		binding.image.setImageBitmap(sourceBitmap);
		if (doRecode) {
			busyRecoding();
			handler.postDelayed(finishCreate, 1000);
		}
	}

	@Override
	public void onConfigurationChanged(@NonNull Configuration config) {
		super.onConfigurationChanged(config);
		changeLayoutOrientation(config);
	}

	private void changeLayoutOrientation(@NonNull Configuration config) {
		if (config.orientation == Configuration.ORIENTATION_LANDSCAPE)
			binding.content.setOrientation(LinearLayout.HORIZONTAL);
		else
			binding.content.setOrientation(LinearLayout.VERTICAL);
	}

	private String[] carrierValues() {
		int count = (maxCarrierFrequency - minCarrierFrequency) / 50 + 1;
		String[] values = new String[count];
		for (int i = 0; i < count; ++i)
			values[i] = String.format(Locale.US, "%d", i * 50 + minCarrierFrequency);
		return values;
	}

	private byte[] callTerm() {
		return Arrays.copyOf(callSign.getBytes(StandardCharsets.US_ASCII), callSign.length() + 1);
	}

	private void setInputType(ViewGroup np, int it) {
		int count = np.getChildCount();
		for (int i = 0; i < count; i++) {
			final View child = np.getChildAt(i);
			if (child instanceof ViewGroup) {
				setInputType((ViewGroup) child, it);
			} else if (child instanceof EditText) {
				EditText et = (EditText) child;
				et.setInputType(it);
				break;
			}
		}
	}

	private final TextWatcher callListener = new TextWatcher() {
		@Override
		public void beforeTextChanged(CharSequence charSequence, int i, int i1, int i2) {

		}

		@Override
		public void onTextChanged(CharSequence charSequence, int i, int i1, int i2) {

		}

		@Override
		public void afterTextChanged(Editable editable) {
			callSign = editable.toString();
		}
	};

	private final Runnable finishFormat = new Runnable() {
		@Override
		public void run() {
			payloadOkay = false;
			if (resizedBitmap != null) {
				payload = encodeBitmap(resizedBitmap);
				if (payload != null) {
					Bitmap bitmap = BitmapFactory.decodeByteArray(payload, 0, payload.length);
					if (bitmap != null) {
						bitmap.setHasAlpha(false);
						binding.image.setImageBitmap(bitmap);
						payloadOkay = payload.length <= payloadSize;
					}
				}
			}
			doneRecoding();
		}
	};

	private final AdapterView.OnItemSelectedListener formatListener = new AdapterView.OnItemSelectedListener() {
		@Override
		public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
			imageFormat = adapterView.getItemAtPosition(i).toString();
			updateCompressionMethodButton(true);
			if (doRecode) {
				busyRecoding();
				handler.post(finishFormat);
			}
		}

		@Override
		public void onNothingSelected(AdapterView<?> adapterView) {

		}
	};

	private final CompoundButton.OnCheckedChangeListener lossyListener = new CompoundButton.OnCheckedChangeListener() {
		@Override
		public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
			lossyCompression = isChecked;
			if (doRecode) {
				busyRecoding();
				handler.post(finishFormat);
			}
		}
	};

	private final Runnable finishPixels = new Runnable() {
		@Override
		public void run() {
			resizedBitmap = resizeBitmap(sourceBitmap);
			payloadOkay = false;
			if (resizedBitmap != null) {
				resizedBitmap.setHasAlpha(false);
				binding.image.setImageBitmap(resizedBitmap);
				payload = encodeBitmap(resizedBitmap);
				if (payload != null) {
					Bitmap bitmap = BitmapFactory.decodeByteArray(payload, 0, payload.length);
					if (bitmap != null) {
						bitmap.setHasAlpha(false);
						binding.image.setImageBitmap(bitmap);
						payloadOkay = payload.length <= payloadSize;
					}
				}
			}
			doneRecoding();
		}
	};

	private final AdapterView.OnItemSelectedListener pixelsListener = new AdapterView.OnItemSelectedListener() {
		@Override
		public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
			pixelCount = adapterView.getItemAtPosition(i).toString();
			if (doRecode) {
				busyRecoding();
				handler.post(finishPixels);
			}
		}

		@Override
		public void onNothingSelected(AdapterView<?> adapterView) {

		}
	};

	private void updateCarriers() {
		int bandWidth = 0;
		switch (operationMode) {
			case 6:
				bandWidth = 2700;
				break;
			case 7:
			case 8:
				bandWidth = 2500;
				break;
			case 9:
				bandWidth = 2250;
				break;
			case 10:
				bandWidth = 3200;
				break;
			case 11:
			case 12:
				bandWidth = 2400;
				break;
			case 13:
				bandWidth = 1600;
				break;
		}
		bandWidth = ((bandWidth + 99) / 100) * 100;
		maxCarrierFrequency = (sampleRate - bandWidth) / 2;
		minCarrierFrequency = channelSelect == 4 ? -maxCarrierFrequency : bandWidth / 2;
		if (carrierFrequency < minCarrierFrequency)
			carrierFrequency = minCarrierFrequency;
		if (carrierFrequency > maxCarrierFrequency)
			carrierFrequency = maxCarrierFrequency;
		binding.carrier.setDisplayedValues(null);
		binding.carrier.setMaxValue((maxCarrierFrequency - minCarrierFrequency) / 50);
		binding.carrier.setValue((carrierFrequency - minCarrierFrequency) / 50);
		binding.carrier.setDisplayedValues(carrierValues());
		setInputType(binding.carrier, InputType.TYPE_CLASS_NUMBER);
	}

	private final AdapterView.OnItemSelectedListener modeListener = new AdapterView.OnItemSelectedListener() {
		@Override
		public void onItemSelected(AdapterView<?> adapterView, View view, int i, long l) {
			operationMode = i + 6;
			updateCarriers();
		}

		@Override
		public void onNothingSelected(AdapterView<?> adapterView) {

		}
	};

	private void doneRecoding() {
		binding.format.setEnabled(true);
		binding.pixels.setEnabled(true);
		binding.mode.setEnabled(true);
		binding.carrier.setEnabled(true);
		binding.call.setEnabled(true);
		updateCompressionMethodButton(false);
		int icon = R.drawable.outline_send_24;
		if (!payloadOkay)
			icon = R.drawable.outline_disc_full_24;
		if (!encoderOkay)
			icon = R.drawable.outline_error_outline_24;
		menu.findItem(R.id.action_encode).setIcon(icon);
		menu.findItem(R.id.action_encode).setEnabled(payloadOkay && encoderOkay);
	}

	private void busyRecoding() {
		binding.format.setEnabled(false);
		binding.pixels.setEnabled(false);
		binding.mode.setEnabled(false);
		binding.carrier.setEnabled(false);
		binding.call.setEnabled(false);
		binding.lossy.setEnabled(false);
		menu.findItem(R.id.action_encode).setIcon(R.drawable.outline_sync_24);
		menu.findItem(R.id.action_encode).setEnabled(false);
	}

	private void busySending() {
		binding.format.setEnabled(false);
		binding.pixels.setEnabled(false);
		binding.mode.setEnabled(false);
		binding.carrier.setEnabled(false);
		binding.call.setEnabled(false);
		binding.lossy.setEnabled(false);
		menu.findItem(R.id.action_encode).setIcon(R.drawable.outline_cancel_24);
	}

	private void doneSending() {
		menu.findItem(R.id.action_encode).setEnabled(false);
		handler.postDelayed(() -> {
			binding.format.setEnabled(doRecode);
			binding.pixels.setEnabled(doRecode);
			binding.mode.setEnabled(true);
			binding.carrier.setEnabled(true);
			binding.call.setEnabled(true);
			updateCompressionMethodButton(false);
			menu.findItem(R.id.action_encode).setIcon(R.drawable.outline_send_24);
			menu.findItem(R.id.action_encode).setEnabled(true);
		}, 1000);
	}

	private void enableRecoding(boolean enable) {
		binding.format.setEnabled(enable);
		binding.pixels.setEnabled(enable);
		if (enable)
			updateCompressionMethodButton(false);
		else
			binding.lossy.setEnabled(false);
		doRecode = enable;
		payloadOkay = !enable;
	}

	@Override
	protected void onSaveInstanceState(@NonNull Bundle state) {
		state.putInt("nightMode", AppCompatDelegate.getDefaultNightMode());
		state.putInt("sampleRate", sampleRate);
		state.putInt("channelSelect", channelSelect);
		state.putInt("operationMode", operationMode);
		state.putInt("carrierFrequency", carrierFrequency);
		state.putInt("noiseSymbols", noiseSymbols);
		state.putString("callSign", callSign);
		state.putString("imageFormat", imageFormat);
		state.putString("pixelCount", pixelCount);
		state.putBoolean("lossyCompression", lossyCompression);
		state.putBoolean("fancyHeader", fancyHeader);
		super.onSaveInstanceState(state);
	}

	private void storeSettings() {
		SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
		SharedPreferences.Editor edit = pref.edit();
		edit.putInt("nightMode", AppCompatDelegate.getDefaultNightMode());
		edit.putInt("sampleRate", sampleRate);
		edit.putInt("channelSelect", channelSelect);
		edit.putInt("operationMode", operationMode);
		edit.putInt("carrierFrequency", carrierFrequency);
		edit.putInt("noiseSymbols", noiseSymbols);
		edit.putString("callSign", callSign);
		edit.putString("imageFormat", imageFormat);
		edit.putString("pixelCount", pixelCount);
		edit.putBoolean("lossyCompression", lossyCompression);
		edit.putBoolean("fancyHeader", fancyHeader);
		edit.apply();
	}

	@Override
	protected void onCreate(Bundle state) {
		final int defaultSampleRate = 8000;
		final int defaultChannelSelect = 0;
		final int defaultOperationMode = 11;
		final int defaultCarrierFrequency = 1700;
		final int defaultNoiseSymbols = 6;
		final String defaultCallSign = "ANONYMOUS";
		final String defaultImageFormat = "WebP";
		final String defaultPixelCount = "64K";
		final boolean defaultLossyCompression = true;
		final boolean defaultFancyHeader = true;
		if (state == null) {
			SharedPreferences pref = getPreferences(Context.MODE_PRIVATE);
			AppCompatDelegate.setDefaultNightMode(pref.getInt("nightMode", AppCompatDelegate.getDefaultNightMode()));
			sampleRate = pref.getInt("sampleRate", defaultSampleRate);
			channelSelect = pref.getInt("channelSelect", defaultChannelSelect);
			operationMode = pref.getInt("operationMode", defaultOperationMode);
			carrierFrequency = pref.getInt("carrierFrequency", defaultCarrierFrequency);
			noiseSymbols = pref.getInt("noiseSymbols", defaultNoiseSymbols);
			callSign = pref.getString("callSign", defaultCallSign);
			imageFormat = pref.getString("imageFormat", defaultImageFormat);
			pixelCount = pref.getString("pixelCount", defaultPixelCount);
			lossyCompression = pref.getBoolean("lossyCompression", defaultLossyCompression);
			fancyHeader = pref.getBoolean("fancyHeader", defaultFancyHeader);
		} else {
			AppCompatDelegate.setDefaultNightMode(state.getInt("nightMode", AppCompatDelegate.getDefaultNightMode()));
			sampleRate = state.getInt("sampleRate", defaultSampleRate);
			channelSelect = state.getInt("channelSelect", defaultChannelSelect);
			operationMode = state.getInt("operationMode", defaultOperationMode);
			carrierFrequency = state.getInt("carrierFrequency", defaultCarrierFrequency);
			noiseSymbols = state.getInt("noiseSymbols", defaultNoiseSymbols);
			callSign = state.getString("callSign", defaultCallSign);
			imageFormat = state.getString("imageFormat", defaultImageFormat);
			pixelCount = state.getString("pixelCount", defaultPixelCount);
			lossyCompression = state.getBoolean("lossyCompression", defaultLossyCompression);
			fancyHeader = state.getBoolean("fancyHeader", defaultFancyHeader);
		}
		super.onCreate(state);
		binding = ActivityMainBinding.inflate(getLayoutInflater());
		changeLayoutOrientation(getResources().getConfiguration());

		ArrayAdapter<CharSequence> formatAdapter = ArrayAdapter.createFromResource(this, R.array.image_types, android.R.layout.simple_spinner_item);
		formatAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		binding.format.setAdapter(formatAdapter);
		binding.format.setSelection(formatAdapter.getPosition(imageFormat), false);
		binding.format.setOnItemSelectedListener(formatListener);

		ArrayAdapter<CharSequence> pixelsAdapter = ArrayAdapter.createFromResource(this, R.array.pixel_count, android.R.layout.simple_spinner_item);
		pixelsAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		binding.pixels.setAdapter(pixelsAdapter);
		binding.pixels.setSelection(pixelsAdapter.getPosition(pixelCount), false);
		binding.pixels.setOnItemSelectedListener(pixelsListener);

		ArrayAdapter<CharSequence> modeAdapter = ArrayAdapter.createFromResource(this, R.array.operation_modes, android.R.layout.simple_spinner_item);
		modeAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		binding.mode.setAdapter(modeAdapter);
		binding.mode.setOnItemSelectedListener(modeListener);
		binding.mode.setSelection(operationMode - 6, false);

		binding.carrier.setMinValue(0);
		updateCarriers();
		binding.carrier.setOnValueChangedListener((numberPicker, oldVal, newVal) -> carrierFrequency = newVal * 50 + minCarrierFrequency);

		binding.call.setText(callSign);
		binding.call.addTextChangedListener(callListener);

		updateCompressionMethodButton(false);

		setContentView(binding.getRoot());
		initAudioTrack();

		InputStream stream = openStream(getIntent());
		if (stream != null)
			sourceBitmap = decodeStream(stream);
		if (stream == null || sourceBitmap == null) {
			stream = getResources().openRawResource(R.raw.smpte_color_bars);
			payload = new byte[payloadSize];
			try {
				int size = stream.available();
				if (size == 0 || size > payloadSize || size != stream.read(payload, 0, size))
					System.exit(0);
				for (int i = size; i < payloadSize; ++i)
					payload[i] = 0;
			} catch (IOException ignore) {
				System.exit(0);
			}
			enableRecoding(false);
			sourceBitmap = BitmapFactory.decodeByteArray(payload, 0, payload.length);
		}
		sourceBitmap.setHasAlpha(false);
		binding.image.setImageBitmap(sourceBitmap);
		handler = new Handler(getMainLooper());
	}

	private final Runnable finishCreate = new Runnable() {
		@Override
		public void run() {
			resizedBitmap = resizeBitmap(sourceBitmap);
			payloadOkay = false;
			if (resizedBitmap != null) {
				resizedBitmap.setHasAlpha(false);
				binding.image.setImageBitmap(resizedBitmap);
				payload = encodeBitmap(resizedBitmap);
				if (payload != null) {
					Bitmap bitmap = BitmapFactory.decodeByteArray(payload, 0, payload.length);
					if (bitmap != null) {
						bitmap.setHasAlpha(false);
						binding.image.setImageBitmap(bitmap);
						payloadOkay = payload.length <= payloadSize;
					}
				}
			}
			doneRecoding();
		}
	};

	private void setSampleRate(int newSampleRate) {
		if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING)
			return;
		if (sampleRate == newSampleRate)
			return;
		sampleRate = newSampleRate;
		updateSampleRateMenu();
		updateCarriers();
		initAudioTrack();
		initEncoder();
	}

	private void updateSampleRateMenu() {
		switch (sampleRate) {
			case 8000:
				menu.findItem(R.id.action_set_rate_8000).setChecked(true);
				break;
			case 16000:
				menu.findItem(R.id.action_set_rate_16000).setChecked(true);
				break;
			case 32000:
				menu.findItem(R.id.action_set_rate_32000).setChecked(true);
				break;
			case 44100:
				menu.findItem(R.id.action_set_rate_44100).setChecked(true);
				break;
			case 48000:
				menu.findItem(R.id.action_set_rate_48000).setChecked(true);
				break;
		}
	}

	private void setNoiseSymbols(int newNoiseSymbols) {
		if (noiseSymbols == newNoiseSymbols)
			return;
		noiseSymbols = newNoiseSymbols;
		updateNoiseSymbolsMenu();
	}

	private void updateNoiseSymbolsMenu() {
		switch (noiseSymbols) {
			case 0:
				menu.findItem(R.id.action_disable_noise).setChecked(true);
				break;
			case 1:
				menu.findItem(R.id.action_set_noise_quarter_second).setChecked(true);
				break;
			case 3:
				menu.findItem(R.id.action_set_noise_half_second).setChecked(true);
				break;
			case 6:
				menu.findItem(R.id.action_set_noise_one_second).setChecked(true);
				break;
			case 11:
				menu.findItem(R.id.action_set_noise_two_seconds).setChecked(true);
				break;
			case 22:
				menu.findItem(R.id.action_set_noise_four_seconds).setChecked(true);
				break;
		}
	}

	private void setFancyHeader(boolean newFancyHeader) {
		if (fancyHeader == newFancyHeader)
			return;
		fancyHeader = newFancyHeader;
		updateFancyHeaderMenu();
	}

	private void updateFancyHeaderMenu() {
		if (fancyHeader)
			menu.findItem(R.id.action_enable_fancy_header).setChecked(true);
		else
			menu.findItem(R.id.action_disable_fancy_header).setChecked(true);
	}

	private void setChannelSelect(int newChannelSelect) {
		if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING)
			return;
		if (channelSelect == newChannelSelect)
			return;
		channelSelect = newChannelSelect;
		updateChannelSelectMenu();
		updateCarriers();
		initAudioTrack();
	}

	private void updateChannelSelectMenu() {
		switch (channelSelect) {
			case 0:
				menu.findItem(R.id.action_set_channel_default).setChecked(true);
				break;
			case 1:
				menu.findItem(R.id.action_set_channel_first).setChecked(true);
				break;
			case 2:
				menu.findItem(R.id.action_set_channel_second).setChecked(true);
				break;
			case 4:
				menu.findItem(R.id.action_set_channel_analytic).setChecked(true);
				break;
		}
	}

	private void updateCompressionMethodButton(boolean formatChanged) {
		binding.lossy.setOnCheckedChangeListener(null);
		switch (imageFormat) {
			case "JPEG":
				binding.lossy.setChecked(true);
				binding.lossy.setEnabled(false);
				lossyCompression = true;
				break;
			case "PNG":
				binding.lossy.setChecked(false);
				binding.lossy.setEnabled(false);
				lossyCompression = false;
				break;
			case "WebP":
				if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q)
					lossyCompression = true;
				if (formatChanged)
					lossyCompression = true;
				binding.lossy.setChecked(lossyCompression);
				binding.lossy.setEnabled(doRecode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q);
				break;
		}
		binding.lossy.setOnCheckedChangeListener(lossyListener);
	}

	@Override
	public boolean onCreateOptionsMenu(Menu menu) {
		getMenuInflater().inflate(R.menu.menu_main, menu);
		this.menu = menu;
		initEncoder();
		updateSampleRateMenu();
		updateChannelSelectMenu();
		updateNoiseSymbolsMenu();
		updateFancyHeaderMenu();
		if (doRecode) {
			busyRecoding();
			handler.postDelayed(finishCreate, 1000);
		}
		return true;
	}

	@Override
	public boolean onOptionsItemSelected(MenuItem item) {
		int id = item.getItemId();
		if (id == R.id.action_ping) {
			configureEncoder(null, callTerm(), 0, carrierFrequency, noiseSymbols, fancyHeader);
			if (audioTrack.getPlayState() != AudioTrack.PLAYSTATE_PLAYING)
				startSending();
			return true;
		}
		if (id == R.id.action_encode) {
			if (audioTrack.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
				audioTrack.stop();
				doneSending();
			} else {
				configureEncoder(payload, callTerm(), operationMode, carrierFrequency, noiseSymbols, fancyHeader);
				startSending();
			}
			return true;
		}
		if (id == R.id.action_set_rate_8000) {
			setSampleRate(8000);
			return true;
		}
		if (id == R.id.action_set_rate_16000) {
			setSampleRate(16000);
			return true;
		}
		if (id == R.id.action_set_rate_32000) {
			setSampleRate(32000);
			return true;
		}
		if (id == R.id.action_set_rate_44100) {
			setSampleRate(44100);
			return true;
		}
		if (id == R.id.action_set_rate_48000) {
			setSampleRate(48000);
			return true;
		}
		if (id == R.id.action_set_channel_default) {
			setChannelSelect(0);
			return true;
		}
		if (id == R.id.action_set_channel_first) {
			setChannelSelect(1);
			return true;
		}
		if (id == R.id.action_set_channel_second) {
			setChannelSelect(2);
			return true;
		}
		if (id == R.id.action_set_channel_analytic) {
			setChannelSelect(4);
			return true;
		}
		if (id == R.id.action_disable_noise) {
			setNoiseSymbols(0);
			return true;
		}
		if (id == R.id.action_set_noise_quarter_second) {
			setNoiseSymbols(1);
			return true;
		}
		if (id == R.id.action_set_noise_half_second) {
			setNoiseSymbols(3);
			return true;
		}
		if (id == R.id.action_set_noise_one_second) {
			setNoiseSymbols(6);
			return true;
		}
		if (id == R.id.action_set_noise_two_seconds) {
			setNoiseSymbols(11);
			return true;
		}
		if (id == R.id.action_set_noise_four_seconds) {
			setNoiseSymbols(22);
			return true;
		}
		if (id == R.id.action_enable_fancy_header) {
			setFancyHeader(true);
			return true;
		}
		if (id == R.id.action_disable_fancy_header) {
			setFancyHeader(false);
			return true;
		}
		if (id == R.id.action_enable_night_mode) {
			AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
			return true;
		}
		if (id == R.id.action_disable_night_mode) {
			AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO);
			return true;
		}
		if (id == R.id.action_force_quit) {
			storeSettings();
			System.exit(0);
			return true;
		}
		if (id == R.id.action_privacy_policy) {
			showTextPage(getString(R.string.privacy_policy), getString(R.string.privacy_policy_text));
			return true;
		}
		if (id == R.id.action_about) {
			showTextPage(getString(R.string.about), getString(R.string.about_text, BuildConfig.VERSION_NAME));
			return true;
		}
		return super.onOptionsItemSelected(item);
	}

	private void startSending() {
		busySending();
		for (int i = 0; i < 5; ++i) {
			produceEncoder(audioBuffer, channelSelect);
			audioTrack.write(audioBuffer, 0, audioBuffer.length);
		}
		audioTrack.play();
	}

	private void showTextPage(String title, String message) {
		AlertDialog.Builder builder = new AlertDialog.Builder(this, R.style.Theme_AlertDialog);
		builder.setNeutralButton(R.string.close, null);
		builder.setTitle(title);
		builder.setMessage(message);
		builder.show();
	}

	@Override
	protected void onPause() {
		storeSettings();
		super.onPause();
	}

	@Override
	protected void onDestroy() {
		audioTrack.stop();
		destroyEncoder();
		super.onDestroy();
	}
}
