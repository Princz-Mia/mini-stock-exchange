import type { components } from './schema';

/** Shapes from the OpenAPI contract; regenerate schema.ts with `npm run api:generate`, never edit it. */
type Schemas = components['schemas'];

export type Problem = Schemas['Problem'];
export type Identity = Schemas['Identity'];
export type CsrfToken = Schemas['CsrfToken'];
export type Instruments = Schemas['Instruments'];
export type Instrument = Schemas['Instrument'];
export type Feed = Schemas['Feed'];
export type Quote = Schemas['Quote'];
export type Portfolio = Schemas['Portfolio'];
export type Holding = Schemas['Holding'];
export type OwnTradePage = Schemas['OwnTradePage'];
export type OwnTrade = Schemas['OwnTrade'];
export type OrderPage = Schemas['OrderPage'];
export type OrderDetail = Schemas['OrderDetail'];
