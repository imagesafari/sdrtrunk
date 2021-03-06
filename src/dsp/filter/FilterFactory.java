/*******************************************************************************
 *     SDR Trunk 
 *     Copyright (C) 2014,2015 Dennis Sheirer
 *
 *	   Root Raised Cosine filter designer:
 *	   Copyright 2002,2007,2008,2012,2013 Free Software Foundation, Inc.
 *	   http://gnuradio.org/redmine/projects/gnuradio/repository/changes/gr-filter
 *	   /lib/firdes.cc?rev=435b1d166f0c7092bbd5e1f788e75dbb6ade3a4b
 *
 *     This program is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 * 
 *     This program is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 * 
 *     You should have received a copy of the GNU General Public License
 *     along with this program.  If not, see <http://www.gnu.org/licenses/>
 ******************************************************************************/
package dsp.filter;

import java.util.Arrays;
import java.util.Set;
import java.util.TreeSet;

import org.jtransforms.fft.FloatFFT_1D;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import dsp.filter.Window.WindowType;
import dsp.filter.cic.ComplexPrimeCICDecimate;

public class FilterFactory
{
	private final static Logger mLog = 
			LoggerFactory.getLogger( FilterFactory.class );

	/**
	 * Generates coefficients for a unity-gain, windowed low-pass filter
	 * @param sampleRate - hertz
	 * @param frequency - cutoff in hertz
	 * @param length - filter length
	 * @param window - to apply against the coefficients
	 * @return
	 */
	public static float[] getSinc( int sampleRate, 
								   long frequency, 
								   int length,
								   WindowType window )
	{
		//Ensure we have an odd length
		assert( length % 2 == 0 );

		//Get unity response array (one element longer to align with IDFT size)
		float[] frequencyResponse = getUnityResponseArray( sampleRate, frequency, length + 1 );

		//Apply Inverse DFT against frequency response unity values, leaving the
		//IDFT bin results in the frequency response array
		FloatFFT_1D idft = new FloatFFT_1D( length + 1 );
		idft.realInverseFull( frequencyResponse, false );

		//Transfer the IDFT results to the return array
		float[] coefficients = new float[ length ];
		int middleCoefficient = (int)( length / 2 );

		//Bin 0 of the idft is our center coefficient
		coefficients[ middleCoefficient ] = frequencyResponse[ 0 ];

		//The remaining idft bins from 1 to (middle - 1) are the mirror image
		//coefficients
		for( int x = 1; x < middleCoefficient; x++ )
		{
			coefficients[ middleCoefficient + x ] = frequencyResponse[ 2 * x ];
			coefficients[ middleCoefficient - x ] = frequencyResponse[ 2 * x ];
		}

		//Apply the window against the coefficients
		coefficients = Window.apply( window, coefficients );

		//Normalize to unity (1) gain
		coefficients = normalize( coefficients );
		
		return coefficients;
	}
	
	/**
	 * Normalizes all filter coefficients to achieve unity (1) gain, by ensuring
	 * that the sum of the absolute value of all coefficients adds up to 1.
	 * @param coefficients
	 * @return
	 */
	private static float[] normalize( float[] coefficients )
	{
		float accumulator = 0;
		
		for( int x = 0; x < coefficients.length; x++ )
		{
			accumulator += Math.abs( coefficients[ x ] );
		}
		
		for( int x = 0; x < coefficients.length; x++ )
		{
			coefficients[ x ] = coefficients[ x ] / accumulator;
		}

		return coefficients;
	}
	
	/**
	 * Constructs an array of unity (1) response values representing the 
	 * desired (pre-windowing) frequency response, used by the sync function
	 * 
	 * Returns an array twice the length, filled with unity (1) response values
	 * in the desired pass-band with the positive frequency response starting at
	 * the lower end of the array, and the negative frequency response at the
	 * higher end of the first half of the array.  The remaining zero-valued 
	 * indexes in the second half will store the results of the JTransforms 
	 * inverse DFT operation
	 * 
	 * @param sampleRate
	 * @param frequency
	 * @param length
	 * @return
	 */
	public static float[] getUnityResponseArray( int sampleRate, 
												 long frequency, 
												 int length )
	{
		float[] unityArray = new float[ length * 2 ];

		int binCount = (int)( ( Math.round( 
				(float)frequency / (float)sampleRate * (float)length ) ) );

		if( length % 2 == 0 ) //even length
		{
			for( int x = 0; x < binCount; x++ )
			{
				unityArray[ x ] = 1.0f;
				unityArray[ length - 1 - x ] = 1.0f;
			}
		}
		else //odd length
		{
			unityArray[ 0 ] = 1.0f;

			for( int x = 1; x <= binCount; x++ )
			{
				unityArray[ x ] = 1.0f;
				unityArray[ length - x ] = 1.0f;
			}
		}

		return unityArray;
	}
	
	public static float[] getSine( float sampleRate, float frequency, int length )
	{
		float[] retVal = new float[ length ];
		
		float radianFrequency = (float)( 2.0d * Math.PI * ( frequency / sampleRate ) );

		int middle = (int)( length / 2 );

		for( int x = 0; x < middle; x++ )
		{
			float val = (float)Math.sin( radianFrequency * x );
			
			retVal[ middle + x ] = val;
			retVal[ middle - x ] = -val;
		}
		
		return retVal;
	}
	
	/**
	 * Applies a repeating sequence of 1, -1 to the coefficients to invert
	 * the frequency response of the filter.  Used in converting a low-pass
	 * filter to a high-pass filter.
	 * 
	 * Inverts the sign of all odd index coefficients ( 1, 3, 5, etc.)
	 * returning:
	 *   Index 0: same
	 *   Index 1: inverted
	 *   Index 2: same
	 *   ...
	 *   Index length - 1: same
	 */
	public static float[] invert( float[] coefficients )
	{
		for( int x = 1; x < coefficients.length; x += 2 )
		{
			coefficients[ x ] = -coefficients[ x ];
		}
		
		return coefficients;
	}
	
	/**
	 * Generates filter coefficients for a unity-gain, odd-length, windowed,
	 * low pass filter with passband from 0-hertz to the cutoff frequency.
	 * 
	 * @param sampleRate - hertz
	 * @param cutoff - frequency in hertz
	 * @param filterLength - odd filter length
	 * @param windowType - window to apply against the generated coefficients
	 * @return
	 */
	public static float[] getLowPass( int sampleRate, 
									  long cutoff, 
									  int filterLength, 
									  WindowType windowType )
	{
		if( filterLength % 2 == 0 ) //even length
		{
			float[] values = getSinc( sampleRate, cutoff, filterLength + 2, windowType );
			
			//throw away the 0 index and the last index
			return Arrays.copyOfRange( values, 1, values.length - 2 );
		}
		else
		{
			float[] values = getSinc( sampleRate, cutoff, filterLength + 1, windowType );
			
			//throw away the 0 index
			return Arrays.copyOfRange( values, 1, values.length );
		}
	}
	
	/**
	 * Creates a low-pass filter with ~ 0.1 db ripple in the pass band
	 * 
	 * Note: stop frequency - pass frequency defines the transition band.
	 */
	/**
	 * Creates a low-pass filter with ~0.1 db ripple in the pass band.  The 
	 * transition region is defined by the stop frequency minus the pass
	 * frequency.
	 * 
	 * Requires:
	 *   - passFrequency < stopFrequency
	 *   - stopFrequency <= sampleRate/2
	 */
	public static float[] getLowPass( int sampleRate,
									  int passFrequency,
									  int stopFrequency,
									  int attenuation,
									  WindowType windowType,
									  boolean forceOddLength )
	{
		if( stopFrequency < passFrequency || stopFrequency > ( sampleRate / 2 ) )
		{
			throw new IllegalArgumentException( "FilterFactory - low pass "
					+ "filter pass frequency [" + passFrequency + "] must be "
					+ "less than the stop frequency [" + stopFrequency + "] "
					+ "and must be less than half [" + (int)( sampleRate / 2 ) + 
					"] of the sample rate [" + sampleRate + "]" );
		}
			
		int tapCount = getTapCount( sampleRate, 
								passFrequency, 
								stopFrequency, 
								attenuation );
		
		if( forceOddLength )
		{
			if( tapCount % 2 == 0 )
			{
				tapCount--;
			}
		}
		
		return getLowPass( sampleRate, passFrequency, tapCount, windowType );
	}

	/**
	 * Generates filter coefficients for a unity-gain, odd-length, windowed,
	 * high pass filter with passband from cutoff frequency to half the sample
	 * rate.
	 * 
	 * @param sampleRate - hertz
	 * @param cutoff - frequency in hertz
	 * @param filterLength - odd filter length
	 * @param windowType - window to apply against the generated coefficients
	 * @return
	 */
	public static float[] getHighPass( int sampleRate, 
									   long cutoff, 
									   int filterLength, 
									   WindowType windowType )
	{
		//Convert the high frequency cutoff to its low frequency cutoff 
		//equivalent, so that when we generate the low pass filter, prior to 
		//inversion, its at the correct frequency
		long convertedCutoff = sampleRate / 2 - cutoff;
		
		return invert( getSinc( sampleRate, 
								convertedCutoff, 
								filterLength, 
								windowType ) );
	}
	
	public static float[] getHighPass( int sampleRate,
									   long stopFrequency,
									   long passFrequency,
									   int attenuation,
									   WindowType windowType,
									   boolean forceOddLength )
	{
		/* reverse the stop and pass frequency to get the low pass variant */
		int tapCount = getTapCount( sampleRate, 
									stopFrequency, 
									passFrequency, 
									attenuation );

		if( forceOddLength )
		{
			if( tapCount % 2 == 0 )
			{
				tapCount--;
			}
		}

		return invert( getLowPass( sampleRate, stopFrequency, tapCount, windowType ) );
	}
	
	/**
	 * Utility to log the arrays of doubles with line breaks
	 */
	public static String arrayToString( float[] array, boolean breaks )
	{
		StringBuilder sb = new StringBuilder();
		for( int x = 0; x < array.length; x++ )
		{
			sb.append( x + ": " + array[ x ] );

			if( breaks )
			{
				sb.append( "\n" );
			}
			else
			{
				if( x % 8 == 7 )
				{
					sb.append( "\n" );
				}
				else
				{
					sb.append( "\t" );
				}
			}
		}
		
		return sb.toString();
	}

	/**
	 * Determines the number of fir filter taps required to produce the 
	 * specified frequency response with passband ripple near .1dB.
	 * 
	 * Implements the algorithm from Understanding Digital Signal Processing, 3e
	 * , Lyons, section 5.10.5.
	 * 
	 * @param sampleRate in hertz
	 * @param pass pass frequency in hertz
	 * @param stop stop frequency in hertz
	 * @param attenuation in dB
	 * @return
	 */
	public static int getTapCount( int sampleRate, 
								   long pass, 
								   long stop, 
								   int attenuation )
	{
		double frequency = ( (double)stop - (double)pass ) / (double)sampleRate;
		
		return (int)( Math.round( (double)attenuation / ( 22.0d * frequency ) ) );
	}
	
	public static ComplexPrimeCICDecimate getDecimationFilter( int sampleRate, 
		int decimatedRate, int order, int passFrequency, int attenuation,	
		WindowType windowType )
	{
		int decimationRate = (int)( sampleRate / decimatedRate );
			
		return new ComplexPrimeCICDecimate( decimationRate, order, 
			passFrequency, attenuation, windowType );
	}
	
	/**
	 * Determines decimation rate(s) for a polyphase decimation filter.
	 * 
	 * @param sampleRate - starting sample rate
	 * @param decimatedRate - final (decimated) output rate
	 * @return - set of integer decimation rates for a single or multi-stage
	 * polyphase filter decimation chain
	 * @throws - AssertionException if sample rate is not a multiple of 48 kHz
	 */
	public static int[] getPolyphaseDecimationRates( int sampleRate, 
													 int decimatedRate,
													 long passFrequency,
													 long stopFrequency )
	{
		int[] rates;

		if( sampleRate % decimatedRate != 0 )
		{
			throw new IllegalArgumentException( "Decimated rate must be an "
					+ "integer multiple of sample rate" );
		}
		
		int decimation = (int)( sampleRate / decimatedRate );

		//Decimation rates below 20 will use single stage polyphase filter
		if( decimation < 20 )
		{
			rates = new int[ 1 ];
			rates[ 0 ] = decimation;
			return rates;
		}
		else
		{
			int optimalStage1 = 
				getOptimalStageOneRate( sampleRate, decimation, passFrequency, stopFrequency );
			
			Set<Integer> factors = getFactors( decimation );
			
			int stage1 = findClosest( optimalStage1, factors );
			
//			mLog.info( "Decimation rate [" + decimation +
//					  "] stage1 optimal [" + optimalStage1 +
//					  "] stage1 actual [" + stage1 +
//					  "]");

			if( stage1 == decimation || stage1 == 1 )
			{
				rates = new int[ 1 ];
				rates[ 0 ] = decimation;
				return rates;
			}
			else
			{
				rates = new int[ 2 ];
				
				int stage2 = (int)( decimation / stage1);

				if( stage1 > stage2 )
				{
					rates[ 0 ] = stage1;
					rates[ 1 ] = stage2;
				}
				else
				{
					rates[ 0 ] = stage2;
					rates[ 1 ] = stage1;
				}
				return rates;
			}
		}
	}
	
	/**
	 * Finds the factor that is closest to the desired factor, from an ordered
	 * list of factors.
	 */
	private static int findClosest( int desiredFactor, Set<Integer> factors )
	{
		int bestFactor = 1;
		int bestDelta = desiredFactor;
		
		for( Integer factor: factors )
		{
			int testDelta = Math.abs( desiredFactor - factor );
			
			if( testDelta < bestDelta )
			{
				bestDelta = testDelta;
				bestFactor = factor;
			}
		}
		
		return bestFactor;
	}
	
	/**
	 * Determines the factors that make up an integer.  Uses a brute force
	 * method to iterate all integers from 1 to value, determining which factors
	 * are evenly divisible into the value.
	 * 
	 * @param value - integer decimation value
	 * @return - ordered set of factors for value
	 */
	private static Set<Integer> getFactors( int value )
	{
		Set<Integer> factors = new TreeSet<Integer>();
		
		/* Brute force */
		for( int x = 1; x <= value; x++ )
		{
			int remainder = (int)( value / x );
			
			if( remainder * x == value )
			{
				factors.add( x );
			}
		}
		
		return factors;
	}

	/**
	 * Determines the optimal decimation rate for stage 1 of a two-stage 
	 * poly-phase decimation filter chain, to produce a final sample rate of 
	 * 48 kHz using a pass bandwidth of 25 kHz.
	 * 
	 * Use for total decimation rates of 20 or higher.
	 * 
	 * Implements the algorithm described in Lyons, Understanding Digital Signal
	 * Processing, 3e, section 10.2.1, page 511.
	 * 
	 * @param sampleRate
	 * @param decimation
	 * @param passFrequency frequency of the pass band
	 * @return optimum integer decimation rate for the first stage decimation
	 * filter
	 */
	public static int getOptimalStageOneRate( int sampleRate, 
											  int decimation,
											  long passFrequency,
											  long stopFrequency )
	{
		double ratio = getBandwidthRatio( passFrequency, stopFrequency );
		
		double numerator = 1.0d - ( 
			Math.sqrt( (double)decimation * ratio / ( 2.0d - ratio )  ) );

		double denominator = 2.0d - ( ratio * ( decimation + 1.0d ) );
		
		int retVal = (int)( 2.0d * decimation * ( numerator / denominator ) );

//		mLog.info( "Optimal Stage 1 Decimation - rate [" + sampleRate +
//				  "] pass [" + passFrequency +
//				  "] bw ratio [" + ratio +
//				  "] optimal [" + retVal +
//				  "]" );
		
		return retVal;
	}
	
	/**
	 * Determines the F ratio as described in Lyons, Understanding Digital
	 * Signal Processing, 3e, section 10.2.1, page 511
	 * 
	 * Used in conjunction with the optimal stage one decimation rate method
	 * above.
	 */
	private static double getBandwidthRatio( long passFrequency, long stopFrequency )
	{
		assert( passFrequency < stopFrequency );

		return ( (double)( stopFrequency - passFrequency ) / 
				 (double)stopFrequency );
	}

	/**
	 * Assumes that the pass band is 1/4 of the output sample rate.  
	 * 
	 * Assumes the stop band is: pass + (pass * .25).
	 * @param outputSampleRate
	 * @param stageCount
	 * @return
	 */
	public static float[] getCICCleanupFilter( int outputSampleRate, 
											   int passFrequency,
											   int attenuation,
											   WindowType window )
	{
		int taps = getTapCount( outputSampleRate, passFrequency, passFrequency + 1500, 
				attenuation );
		
		/* Make tap count odd */
		if( taps% 2 == 0 )
		{
			taps ++;
		}

		float[] frequencyResponse = 
				getCICResponseArray( outputSampleRate, passFrequency, taps );
		
		//Apply Inverse DFT against frequency response unity values, leaving the
		//IDFT bin results in the frequency response array
		FloatFFT_1D idft = new FloatFFT_1D( taps );
		idft.realInverseFull( frequencyResponse, false );

		//Transfer the IDFT results to the odd length return array
		float[] coefficients = new float[ taps ];
		int middleCoefficient = (int)( taps / 2 );

		//Bin 0 of the idft is our center coefficient
		coefficients[ middleCoefficient ] = frequencyResponse[ 0 ];

		//The remaining idft bins from 1 to (middle - 1) are the mirror image
		//coefficients
		for( int x = 1; x <= middleCoefficient; x++ )
		{
			coefficients[ middleCoefficient + x ] = frequencyResponse[ 2 * x ];
			coefficients[ middleCoefficient - x ] = frequencyResponse[ 2 * x ];
		}

		//Apply the window against the coefficients
//		coefficients = Window.apply( window, coefficients );
		
		normalize( coefficients );
		
		return coefficients;
	}

	public static float[] getCICResponseArray( int sampleRate, 
												int frequency, 
												int length )
	{
		float[] cicArray = new float[ length * 2 ];
		
		int binCount = (int)( ( Math.round( 
		(double)frequency / (double)sampleRate * 2.0d * (double)length ) ) );
		
		cicArray[ 0 ] = 1.0f;
		
		float unityResponse = (float)( Math.sin( 1.0d / (double)length ) / 
		   ( 1.0d / (double)length ) );
		
		for( int x = 1; x <= binCount; x++ )
		{
			/* Calculate unity response plus amplification for droop */
			float compensated = 1.0f + ( unityResponse - 
					(float)( Math.sin( (double)x / (double)length ) / 
							( (double)x / (double)length ) ) );
			
			cicArray[ x ] = compensated;
			cicArray[ length - x ] = compensated;
		}

		return cicArray;
	}

	/**
	 * Creates root raised cosine filter coefficients with a tap count equal
	 * to the symbols x samplesPerSymbol + 1.
	 * 
	 * Symbol count should be an even integer.
	 * 
	 * Ported to java from gnuradio/filter/firdes.cc
	 * 
	 * For 40db attenuation, calculate the number of symbols based on the
	 * following formula:
	 * 
	 * Symbols = -44 * alpha + 33
	 * 
	 * @param samplesPerSymbol - number of samples per symbol
	 * @param symbols - number of symbols - must be even
	 * @param alpha - roll-off factor
	 * 
	 * @return - filter coefficients
	 */
	public static float[] getRootRaisedCosine( int samplesPerSymbol,
											   int symbols,
											   float alpha )
	{
		int taps = samplesPerSymbol * symbols + 1;
		
		float scale = 0;
		
		float[] coefficients = new float[ taps ];
		
		for( int x = 0; x < taps; x++ )
		{
			float index = (float)x - ( (float)taps / 2.0f );
			
			float x1 = (float)Math.PI * index / (float)samplesPerSymbol;
			float x2 = 4.0f * alpha * index / (float)samplesPerSymbol;
			float x3 = x2 * x2 - 1.0f;

			float numerator, denominator;
			
			if( Math.abs( x3 ) >= 0.000001 )
			{
				if( x != taps / 2 )
				{
					numerator = (float)Math.cos( ( 1.0 + alpha ) * x1 ) + 
							(float)Math.sin( ( 1.0f - alpha ) * x1 ) / 
						  	( 4.0f * alpha * index / (float)samplesPerSymbol );
				}
				else
				{
					numerator = (float)Math.cos( ( 1.0f + alpha ) * x1 ) +
						  ( 1.0f - alpha ) * (float)Math.PI / ( 4.0f * alpha );
				}
				
				denominator = x3 * (float)Math.PI;
			}
			else
			{
				if( alpha == 1.0f )
				{
					coefficients[ x ] = -1.0f;
					continue;
				}
				
				x3 = ( 1.0f - alpha ) * x1;
				x2 = ( 1.0f + alpha ) * x1;
				
				numerator = (float)( Math.sin( x2 ) * ( 1.0f + alpha ) * Math.PI -
					Math.cos( x3 ) * ( ( 1.0 - alpha ) * Math.PI * 
						(double)samplesPerSymbol ) / ( 4 * alpha * index ) +
					Math.sin( x3 ) * (double)samplesPerSymbol * 
						(double)samplesPerSymbol / ( 4.0 * alpha * index * index ) );
				
				denominator = (float)( -32.0 * Math.PI * alpha * alpha * index / 
						(double)samplesPerSymbol );
			}
			
			coefficients[ x ] = 4.0f * alpha * numerator / denominator;
			
			scale += coefficients[ x ];
		}

		/**
		 * Normalize (scale) coefficients to 1.0 sum and apply gain 
		 */
		for( int x = 0; x < taps; x++ )
		{
			coefficients[ x ] = coefficients[ x ] / scale;
		}

		return coefficients;
	}
}
