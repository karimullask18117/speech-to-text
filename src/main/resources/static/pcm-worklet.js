// Downsamples mic audio to 16 kHz mono PCM16 and posts ~128 ms chunks to the main thread.
class PcmWorklet extends AudioWorkletProcessor {
  constructor() {
    super();
    this.ratio = sampleRate / 16000;
    this.pos = 0;
    this.out = [];
  }
  process(inputs) {
    const ch = inputs[0] && inputs[0][0];
    if (!ch) return true;
    // Box-filter downsample: average every `ratio` input samples.
    for (let i = 0; i < ch.length; i++) {
      this.acc = (this.acc || 0) + ch[i];
      this.cnt = (this.cnt || 0) + 1;
      this.pos += 1;
      if (this.pos >= this.ratio) {
        this.pos -= this.ratio;
        const v = Math.max(-1, Math.min(1, this.acc / this.cnt));
        this.out.push(v < 0 ? v * 0x8000 : v * 0x7fff);
        this.acc = 0; this.cnt = 0;
      }
    }
    if (this.out.length >= 2048) {
      const pcm = Int16Array.from(this.out.splice(0, 2048));
      this.port.postMessage(pcm.buffer, [pcm.buffer]);
    }
    return true;
  }
}
registerProcessor('pcm-worklet', PcmWorklet);
